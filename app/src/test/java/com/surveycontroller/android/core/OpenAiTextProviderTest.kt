package com.surveycontroller.android.core

import com.surveycontroller.android.core.ai.OpenAiTextProvider
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.network.HttpResponse
import com.surveycontroller.android.core.questions.AiTextException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAiTextProviderTest {

    @Test
    fun custom_ai_responses_protocol_uses_responses_endpoint_and_output_text() = runBlocking {
        val http = RecordingHttpClient(
            responseBody = """{"output_text":"答案一\n答案二"}""",
        )
        val provider = OpenAiTextProvider(
            http = http,
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            model = "model-x",
            systemPrompt = "系统提示",
            apiProtocol = "responses",
        )

        val answers = provider.generate(
            SurveyQuestionMeta(num = 1, title = "请填写"),
            blankCount = 2,
            threadName = "T1",
        )

        assertEquals("https://api.example.com/v1/responses", http.lastUrl)
        assertEquals(listOf("答案一", "答案二"), answers)
        val body = JSONObject(http.lastBody)
        assertTrue(body.has("input"))
        assertEquals("系统提示", body.getString("instructions"))
        assertEquals("model-x", body.getString("model"))
    }

    @Test
    fun custom_ai_chat_protocol_keeps_explicit_chat_endpoint() = runBlocking {
        val http = RecordingHttpClient(
            responseBody = """{"choices":[{"message":{"content":"只要这个答案"}}]}""",
        )
        val provider = OpenAiTextProvider(
            http = http,
            baseUrl = "https://api.example.com/v1/chat/completions",
            apiKey = "sk-test",
            model = "model-x",
            systemPrompt = "",
            apiProtocol = "auto",
        )

        val answers = provider.generate(
            SurveyQuestionMeta(num = 1, title = "请填写"),
            blankCount = 1,
            threadName = "T1",
        )

        assertEquals("https://api.example.com/v1/chat/completions", http.lastUrl)
        assertEquals(listOf("只要这个答案"), answers)
        assertTrue(JSONObject(http.lastBody).has("messages"))
    }

    @Test
    fun custom_ai_auto_protocol_falls_back_to_responses_when_chat_endpoint_mismatches() = runBlocking {
        val http = QueueHttpClient(
            HttpResponse(statusCode = 404, body = """{"error":"not found"}""", finalUrl = "https://api.example.com/v1/chat/completions"),
            HttpResponse(statusCode = 200, body = """{"output_text":"Responses答案"}""", finalUrl = "https://api.example.com/v1/responses"),
        )
        val provider = OpenAiTextProvider(
            http = http,
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            model = "model-x",
            systemPrompt = "",
            apiProtocol = "auto",
        )

        val answers = provider.generate(
            SurveyQuestionMeta(num = 1, title = "请填写"),
            blankCount = 1,
            threadName = "T1",
        )

        assertEquals(
            listOf("https://api.example.com/v1/chat/completions", "https://api.example.com/v1/responses"),
            http.urls,
        )
        assertTrue(JSONObject(http.bodies[0]).has("messages"))
        assertTrue(JSONObject(http.bodies[1]).has("input"))
        assertEquals(listOf("Responses答案"), answers)
    }

    @Test
    fun custom_ai_auto_protocol_keeps_explicit_chat_endpoint_failure() = runBlocking {
        val http = QueueHttpClient(
            HttpResponse(statusCode = 404, body = """{"error":"not found"}""", finalUrl = "https://api.example.com/v1/chat/completions"),
        )
        val provider = OpenAiTextProvider(
            http = http,
            baseUrl = "https://api.example.com/v1/chat/completions",
            apiKey = "sk-test",
            model = "model-x",
            systemPrompt = "",
            apiProtocol = "auto",
        )

        try {
            provider.generate(SurveyQuestionMeta(num = 1, title = "请填写"), blankCount = 1, threadName = "T1")
            fail("Expected AiTextException")
        } catch (e: AiTextException) {
            assertTrue(e.message.orEmpty().contains("HTTP 404"))
        }
        assertEquals(listOf("https://api.example.com/v1/chat/completions"), http.urls)
    }

    @Test
    fun custom_ai_rejects_legacy_completions_endpoint_with_clear_error() = runBlocking {
        val http = QueueHttpClient()
        val provider = OpenAiTextProvider(
            http = http,
            baseUrl = "https://api.example.com/v1/completions",
            apiKey = "sk-test",
            model = "model-x",
            systemPrompt = "",
            apiProtocol = "auto",
        )

        try {
            provider.generate(SurveyQuestionMeta(num = 1, title = "请填写"), blankCount = 1, threadName = "T1")
            fail("Expected AiTextException")
        } catch (e: AiTextException) {
            assertTrue(e.message.orEmpty().contains("/completions"))
        }
        assertTrue(http.urls.isEmpty())
    }

    @Test
    fun custom_ai_chat_response_accepts_content_parts_array() = runBlocking {
        val http = RecordingHttpClient(
            responseBody = """{"choices":[{"message":{"content":[{"type":"text","text":"第一行"},{"type":"text","text":"第二行"}]}}]}""",
        )
        val provider = OpenAiTextProvider(
            http = http,
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            model = "model-x",
            systemPrompt = "",
            apiProtocol = "chat_completions",
        )

        val answers = provider.generate(
            SurveyQuestionMeta(num = 1, title = "请填写"),
            blankCount = 2,
            threadName = "T1",
        )

        assertEquals(listOf("第一行", "第二行"), answers)
    }

    @Test
    fun custom_ai_prefill_returns_empty_map_for_empty_questions() = runBlocking {
        // v4.0.3：空题列表直接返回空 map，不发起任何 HTTP 调用
        val http = QueueHttpClient()
        val provider = OpenAiTextProvider(
            http = http,
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            model = "model-x",
            systemPrompt = "",
            apiProtocol = "responses",
        )

        val result = provider.prefill(emptyList(), "T1")

        assertTrue(result.isEmpty())
        assertTrue(http.urls.isEmpty())
    }

    @Test
    fun custom_ai_prefill_collects_answers_for_each_question_concurrently() = runBlocking {
        // v4.0.3：自定义服务商批量预填 —— Semaphore(4) 限流，逐题调 generate，全部成功后返回汇总 map
        val http = QueueHttpClient(
            HttpResponse(statusCode = 200, body = """{"output_text":"答案"}""", finalUrl = "https://api.example.com/v1/responses"),
            HttpResponse(statusCode = 200, body = """{"output_text":"答案"}""", finalUrl = "https://api.example.com/v1/responses"),
            HttpResponse(statusCode = 200, body = """{"output_text":"答案"}""", finalUrl = "https://api.example.com/v1/responses"),
        )
        val provider = OpenAiTextProvider(
            http = http,
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            model = "model-x",
            systemPrompt = "",
            apiProtocol = "responses",
        )
        val questions = listOf(
            SurveyQuestionMeta(num = 1, title = "第一题") to 1,
            SurveyQuestionMeta(num = 2, title = "第二题") to 1,
            SurveyQuestionMeta(num = 3, title = "第三题") to 1,
        )

        val result = provider.prefill(questions, "T1")

        assertEquals(3, result.size)
        assertEquals(listOf("答案"), result[1])
        assertEquals(listOf("答案"), result[2])
        assertEquals(listOf("答案"), result[3])
        assertEquals(3, http.urls.size)
        assertTrue(http.urls.all { it == "https://api.example.com/v1/responses" })
    }

    @Test
    fun custom_ai_prefill_throws_when_any_question_fails() = runBlocking {
        // v4.0.3：任一题目调用失败时收集到 failed，非空则抛 AiTextException 停止本轮提交
        val http = QueueHttpClient(
            HttpResponse(statusCode = 200, body = """{"output_text":"答案"}""", finalUrl = "https://api.example.com/v1/responses"),
            HttpResponse(statusCode = 500, body = """{"error":"server error"}""", finalUrl = "https://api.example.com/v1/responses"),
            HttpResponse(statusCode = 200, body = """{"output_text":"答案"}""", finalUrl = "https://api.example.com/v1/responses"),
        )
        val provider = OpenAiTextProvider(
            http = http,
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            model = "model-x",
            systemPrompt = "",
            apiProtocol = "responses",
        )
        val questions = listOf(
            SurveyQuestionMeta(num = 1, title = "第一题") to 1,
            SurveyQuestionMeta(num = 2, title = "第二题") to 1,
            SurveyQuestionMeta(num = 3, title = "第三题") to 1,
        )

        try {
            provider.prefill(questions, "T1")
            fail("Expected AiTextException")
        } catch (e: AiTextException) {
            assertTrue(e.message.orEmpty().contains("AI 批量预取未完成"))
            assertTrue(e.message.orEmpty().contains("HTTP 500"))
        }
    }

    @Test
    fun custom_ai_prefill_normalizes_multi_blank_answers_using_delimiter() = runBlocking {
        // v4.0.3：multi 题（blankCount > 1）用 || 拆分校验 —— generate 按换行返回多项，prefill 用 || 拼接后 normalize 拆回
        val http = RecordingHttpClient(
            responseBody = """{"output_text":"答案A\n答案B"}""",
        )
        val provider = OpenAiTextProvider(
            http = http,
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            model = "model-x",
            systemPrompt = "",
            apiProtocol = "responses",
        )
        val questions = listOf(
            SurveyQuestionMeta(num = 1, title = "姓名和电话") to 2,
        )

        val result = provider.prefill(questions, "T1")

        assertEquals(listOf("答案A", "答案B"), result[1])
    }

    @Test
    fun multi_blank_parses_json_array_response() = runBlocking {
        // v5 起多空题要求模型输出 JSON 字符串数组
        val http = RecordingHttpClient(
            responseBody = """{"choices":[{"message":{"content":"[\"答案一\", \"答案二\"]"}}]}""",
        )
        val provider = OpenAiTextProvider(http, "https://api.example.com/v1", "sk-test", "model-x", "")

        val answers = provider.generate(
            SurveyQuestionMeta(num = 1, title = "请填写姓名与电话"),
            blankCount = 2,
            threadName = "T1",
        )

        assertEquals(listOf("答案一", "答案二"), answers)
        assertTrue(JSONObject(http.lastBody).toString().contains("JSON 字符串数组"))
    }

    @Test
    fun multi_blank_still_accepts_legacy_separator() = runBlocking {
        val http = RecordingHttpClient(
            responseBody = """{"choices":[{"message":{"content":"答案一||答案二"}}]}""",
        )
        val provider = OpenAiTextProvider(http, "https://api.example.com/v1", "sk-test", "model-x", "")

        val answers = provider.generate(
            SurveyQuestionMeta(num = 1, title = "请填写姓名与电话"),
            blankCount = 2,
            threadName = "T1",
        )

        assertEquals(listOf("答案一", "答案二"), answers)
    }

    @Test
    fun retries_on_rate_limit_then_succeeds() = runBlocking {
        val http = QueueHttpClient(
            HttpResponse(statusCode = 429, body = "{}", finalUrl = ""),
            HttpResponse(
                statusCode = 200,
                body = """{"choices":[{"message":{"content":"恢复后的答案"}}]}""",
                finalUrl = "",
            ),
        )
        val provider = OpenAiTextProvider(http, "https://api.example.com/v1", "sk-test", "model-x", "")

        val answers = provider.generate(SurveyQuestionMeta(num = 1, title = "请填写"), blankCount = 1, threadName = "T1")

        assertEquals(listOf("恢复后的答案"), answers)
        assertEquals(2, http.urls.size)
    }

    @Test
    fun does_not_retry_on_configuration_error() = runBlocking {
        val http = RecordingHttpClient("{}")
        val provider = OpenAiTextProvider(http, "", "sk-test", "model-x", "")

        try {
            provider.generate(SurveyQuestionMeta(num = 1, title = "请填写"), blankCount = 1, threadName = "T1")
            fail("应当抛出配置错误")
        } catch (e: AiTextException) {
            assertEquals("自定义 AI Base URL 为空", e.message)
            assertTrue(!e.retryable)
        }
        assertEquals("", http.lastUrl)
    }

    private class RecordingHttpClient(private val responseBody: String) : HttpClient() {
        var lastUrl: String = ""
        var lastBody: String = ""

        override suspend fun postBody(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
            proxyAddress: String?,
            surveySubmission: Boolean,
        ): HttpResponse {
            lastUrl = url
            lastBody = body
            return HttpResponse(statusCode = 200, body = responseBody, finalUrl = url)
        }
    }

    private class QueueHttpClient(vararg responses: HttpResponse) : HttpClient() {
        private val queue = ArrayDeque(responses.toList())
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        override suspend fun postBody(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
            proxyAddress: String?,
            surveySubmission: Boolean,
        ): HttpResponse {
            urls.add(url)
            bodies.add(body)
            return queue.removeFirstOrNull() ?: HttpResponse(statusCode = 500, body = "{}", finalUrl = url)
        }
    }
}
