package com.surveycontroller.android.core.ai

import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.questions.AiTextException
import com.surveycontroller.android.core.questions.AiTextProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容接口的 AI 填空实现。对应桌面端 software/core/ai/runtime.py 的自定义 API 模式。
 */
class OpenAiTextProvider(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    private val apiProtocol: String = "auto",
) : AiTextProvider {

    private companion object {
        const val PREFILL_MAX_CONCURRENCY = 4

        /** 对齐官方 v5 `_AI_FILL_MAX_ATTEMPTS`。 */
        const val MAX_ATTEMPTS = 4

        /** 对齐官方 v5 `_AI_FILL_RETRY_BACKOFF_SECONDS`。 */
        const val RETRY_BACKOFF_MS = 400L
    }

    override suspend fun generate(question: SurveyQuestionMeta, blankCount: Int, threadName: String): List<String> {
        val endpoint = resolveEndpoint(baseUrl, apiProtocol)
        val sys = systemPrompt.ifBlank {
            "你是问卷填写助手。根据题目生成简短、自然、合理的中文作答内容，只输出答案本身，不要解释。"
        }
        val expected = maxOf(1, blankCount)
        val userPrompt = buildString {
            append("题目：").append(question.title.ifBlank { "请作答" })
            if (expected > 1) {
                // v5 起多空题要求输出 JSON 字符串数组（原为 || 分隔）
                append("\n需要生成 $expected 条作答，按顺序只输出 JSON 字符串数组（示例：[\"答案1\", \"答案2\"]）。")
            }
        }
        var attempt = 1
        while (true) {
            try {
                val content = try {
                    execute(endpoint.protocol, endpoint.url, sys, userPrompt)
                } catch (e: AiTextException) {
                    if (!shouldFallbackToResponses(endpoint, e)) throw e
                    execute("responses", endpoint.responsesFallbackUrl, sys, userPrompt)
                }
                return normalizeAnswers(content, expected)
            } catch (e: Exception) {
                val error = e as? AiTextException
                    ?: AiTextException("自定义 AI 填空失败：${e.message ?: e::class.java.simpleName}", e, retryable = true)
                if (!error.retryable || attempt >= MAX_ATTEMPTS) throw error
                kotlinx.coroutines.delay(RETRY_BACKOFF_MS * attempt)
                attempt += 1
            }
        }
    }

    /**
     * 解析模型输出，优先 JSON 字符串数组，其次换行 / `||` 兜底。
     * 数量不足时用最后一条补齐（与旧版行为一致），过多则截断。
     */
    private fun normalizeAnswers(raw: String, blankCount: Int): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) throw AiTextException("自定义 AI 未返回可用填空内容", retryable = true)
        val parsed = (if (blankCount > 1) parseJsonArray(text) else null) ?: splitFallback(text)
        val answers = parsed.map { it.trim().trim('"', '“', '”') }.filter { it.isNotEmpty() }
        if (answers.isEmpty()) throw AiTextException("自定义 AI 未返回可用填空内容", retryable = true)
        return when {
            answers.size >= blankCount -> answers.take(blankCount)
            else -> answers + List(blankCount - answers.size) { answers.last() }
        }
    }

    private fun parseJsonArray(text: String): List<String>? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try {
            val arr = JSONArray(text.substring(start, end + 1))
            (0 until arr.length()).mapNotNull { i ->
                when (val value = arr.opt(i)) {
                    null, JSONObject.NULL -> null
                    is String -> value
                    else -> value.toString()
                }
            }.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun splitFallback(text: String): List<String> =
        text.split("\n", "||").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * v4.0.3：自定义服务商批量预填。复刻 software/core/ai/batch_runtime.py:_wait_provider_batch_result_async。
     * 信号量限流 = 4，逐题调 generate；失败题收集到 failed，若非空抛异常停止本轮提交。
     */
    override suspend fun prefill(
        questions: List<Pair<SurveyQuestionMeta, Int>>,
        threadName: String,
    ): Map<Int, List<String>> {
        if (questions.isEmpty()) return emptyMap()
        val semaphore = Semaphore(PREFILL_MAX_CONCURRENCY)
        val completed = linkedMapOf<Int, List<String>>()
        val failed = linkedMapOf<Int, String>()
        coroutineScope {
            questions.map { (q, blankCount) ->
                async {
                    semaphore.withPermit {
                        try {
                            val answers = generate(q, blankCount, threadName)
                            completed[q.num] = normalizeProviderItemAnswers(answers, blankCount)
                        } catch (e: Exception) {
                            failed[q.num] = e.message ?: "AI 调用失败"
                        }
                    }
                }
            }.awaitAll()
        }
        if (failed.isNotEmpty()) {
            val detail = failed.entries.take(5).joinToString("；") { "第${it.key}题：${it.value}" }
            throw AiTextException("AI 批量预取未完成，已停止本轮提交：$detail")
        }
        return completed
    }

    /**
     * v4.0.3：复刻 batch_runtime.py:_normalize_provider_item_answers。
     * v5 起答案已是列表（JSON 数组协议），这里只做空值与数量校验。
     */
    private fun normalizeProviderItemAnswers(rawAnswers: List<String>, blankCount: Int): List<String> {
        val answers = rawAnswers.map { it.trim() }.filter { it.isNotEmpty() }
        if (answers.isEmpty()) throw AiTextException("AI 未返回有效答案", retryable = true)
        if (blankCount > 0 && answers.size != blankCount) {
            throw AiTextException("期望 $blankCount 个答案，实际返回 ${answers.size} 个", retryable = true)
        }
        return answers
    }

    private suspend fun execute(protocol: String, endpoint: String, sys: String, userPrompt: String): String {
        val body = if (protocol == "responses") responsesBody(sys, userPrompt) else chatBody(sys, userPrompt)
        val resp = http.postBody(
            url = endpoint,
            body = body.toString(),
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json",
            ),
            timeoutSeconds = 30,
        )
        if (resp.statusCode !in 200..299) {
            val status = resp.statusCode
            // 对齐 v5：仅限流与 5xx 值得重试，其余 4xx 多为配置问题
            throw AiTextException(
                "自定义 AI 返回 HTTP $status",
                retryable = status == 429 || status >= 500,
                statusCode = status,
            )
        }
        val payload = JSONObject(resp.body.ifBlank { "{}" })
        return if (protocol == "responses") {
            extractResponsesText(payload)
        } else {
            extractChatCompletionText(payload)
        }
    }

    private fun chatBody(sys: String, userPrompt: String): JSONObject =
        JSONObject()
            .put("model", model.ifBlank { "gpt-3.5-turbo" })
            .put("max_tokens", 200)
            .put("temperature", 0.7)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", userPrompt)),
            )

    private fun responsesBody(sys: String, userPrompt: String): JSONObject =
        JSONObject()
            .put("model", model.ifBlank { "gpt-3.5-turbo" })
            .put("instructions", sys)
            .put("input", userPrompt)
            .put("max_output_tokens", 200)
            .put("temperature", 0.7)

    private data class EndpointResolution(
        val protocol: String,
        val url: String,
        val hasExplicitEndpoint: Boolean,
        val requestedProtocol: String,
        val baseUrl: String,
    ) {
        val responsesFallbackUrl: String get() = "$baseUrl/responses"
    }

    private fun shouldFallbackToResponses(endpoint: EndpointResolution, error: AiTextException): Boolean =
        endpoint.requestedProtocol == "auto" &&
            !endpoint.hasExplicitEndpoint &&
            isEndpointMismatch(error.message.orEmpty())

    private fun resolveEndpoint(rawBaseUrl: String, rawProtocol: String): EndpointResolution {
        val normalized = rawBaseUrl.trim().trimEnd('/')
        if (normalized.isEmpty()) {
            throw AiTextException("自定义 AI Base URL 为空")
        }
        val lower = normalized.lowercase()
        if (lower.endsWith("/chat/completions")) {
            return EndpointResolution("chat_completions", normalized, hasExplicitEndpoint = true, requestedProtocol = normalizeProtocol(rawProtocol), baseUrl = baseEndpointUrl(normalized, "/chat/completions"))
        }
        if (lower.endsWith("/responses")) {
            return EndpointResolution("responses", normalized, hasExplicitEndpoint = true, requestedProtocol = normalizeProtocol(rawProtocol), baseUrl = baseEndpointUrl(normalized, "/responses"))
        }
        if (lower.endsWith("/completions") && !lower.endsWith("/chat/completions")) {
            throw AiTextException("暂不支持旧版 /completions 协议，请改用 /chat/completions 或 /responses")
        }
        val protocol = normalizeProtocol(rawProtocol)
        return if (protocol == "responses") {
            EndpointResolution("responses", "$normalized/responses", hasExplicitEndpoint = false, requestedProtocol = protocol, baseUrl = normalized)
        } else {
            EndpointResolution("chat_completions", "$normalized/chat/completions", hasExplicitEndpoint = false, requestedProtocol = protocol, baseUrl = normalized)
        }
    }

    private fun normalizeProtocol(rawProtocol: String): String =
        when (rawProtocol.trim().lowercase()) {
            "responses" -> "responses"
            "chat_completions" -> "chat_completions"
            else -> "auto"
        }

    private fun baseEndpointUrl(endpoint: String, suffix: String): String =
        endpoint.removeSuffix(suffix).trimEnd('/')

    private fun extractChatCompletionText(payload: JSONObject): String {
        val choices = payload.optJSONArray("choices") ?: return ""
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
        return extractTextParts(message.opt("content")).joinToString("\n").trim()
    }

    private fun extractResponsesText(payload: JSONObject): String {
        payload.optString("output_text").trim().takeIf { it.isNotEmpty() }?.let { return it }
        val output = payload.optJSONArray("output") ?: return ""
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                val text = extractTextParts(part).joinToString("\n").trim()
                if (text.isNotEmpty()) return text
            }
        }
        return ""
    }

    private fun extractTextParts(value: Any?): List<String> {
        if (value is String) return listOf(value.trim()).filter { it.isNotEmpty() }
        if (value is JSONArray) {
            val parts = mutableListOf<String>()
            for (i in 0 until value.length()) {
                parts.addAll(extractTextParts(value.opt(i)))
            }
            return parts
        }
        if (value is JSONObject) {
            val type = value.optString("type").trim().lowercase()
            if (type !in setOf("text", "output_text", "input_text")) return emptyList()
            val text = value.optString("text").ifBlank { value.optString("content") }.trim()
            return listOf(text).filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    private fun isEndpointMismatch(message: String): Boolean {
        val lower = message.lowercase()
        return listOf(
            "404",
            "405",
            "410",
            "not found",
            "no route",
            "no handler",
            "unsupported path",
            "invalid url",
            "method not allowed",
        ).any { lower.contains(it) }
    }
}
