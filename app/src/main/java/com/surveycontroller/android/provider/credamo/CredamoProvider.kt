package com.surveycontroller.android.provider.credamo

import com.surveycontroller.android.core.engine.AnswerDurationSampler
import com.surveycontroller.android.core.model.AnswerAction
import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.model.ExecutionState
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.network.UserAgents
import com.surveycontroller.android.core.questions.AnswerContext
import com.surveycontroller.android.core.questions.SurveyAnswerBuilder
import com.surveycontroller.android.provider.ProviderAvailability
import com.surveycontroller.android.provider.SubmitResult
import com.surveycontroller.android.provider.SurveyDefinition
import com.surveycontroller.android.provider.SurveyProvider
import com.surveycontroller.android.provider.SurveyProviderType
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Credamo 见数适配器。复刻 credamo/provider/{parser,http_runtime}.py。
 */
class CredamoProvider(
    private val http: HttpClient,
    private val answerContextFactory: (ExecutionConfig, ExecutionState, String) -> AnswerContext =
        { c, s, t -> AnswerContext(c, s, t) },
) : SurveyProvider {

    override val type = SurveyProviderType.CREDAMO
    private val timeoutSeconds = 30L

    override suspend fun parseSurvey(url: String): SurveyDefinition {
        val origin = CredamoApi.originFromUrl(url)
        val shortUrl = CredamoApi.noauthShortUrl(CredamoApi.shortUrlFromUrl(url))
        val headers = CredamoApi.requestHeaders(origin, shortUrl)
        val detail = fetchDetail(origin, shortUrl, headers)
        val rawQuestions = CredamoApi.iterRawQuestions(detail)
        if (rawQuestions.isEmpty()) error("见数详情接口未返回可解析题目，请确认链接为免登录问卷且已开放")
        val (metas, _) = CredamoApi.standardize(rawQuestions)
        return SurveyDefinition(SurveyProviderType.CREDAMO, url, CredamoApi.surveyTitle(detail), metas)
    }

    override suspend fun fillSurveyHttp(
        config: ExecutionConfig,
        state: ExecutionState,
        threadName: String,
        proxyAddress: String?,
        userAgent: String?,
    ): SubmitResult {
        val origin = CredamoApi.originFromUrl(config.url)
        val shortUrl = CredamoApi.noauthShortUrl(CredamoApi.shortUrlFromUrl(config.url))
        val ua = UserAgents.resolve(userAgent)

        state.updateThreadStatus(threadName, "加载问卷", running = true)
        val detail = try {
            fetchDetail(origin, shortUrl, CredamoApi.requestHeaders(origin, shortUrl, ua), proxyAddress)
        } catch (e: CredamoUnavailableException) {
            return SubmitResult.ProviderUnavailable(e.message ?: "见数当前不可填写")
        }
        val rawQuestions = CredamoApi.iterRawQuestions(detail)
        if (rawQuestions.isEmpty()) return SubmitResult.Failure("详情接口未返回可提交题目")
        val (runtimeMetas, rawByNum) = CredamoApi.standardize(rawQuestions)
        runtimeMetas.firstOrNull { it.unsupported && !it.isDescription }?.let { q ->
            val reason = q.unsupportedReason.ifBlank { q.providerType.ifBlank { q.typeCode } }
            return SubmitResult.Failure("见数第${q.num}题暂不支持：$reason")
        }
        val submitQuestions = try {
            mergeSubmitQuestions(config, rawByNum)
        } catch (e: Exception) {
            return SubmitResult.Failure(e.message ?: "见数运行时题目匹配失败")
        }

        state.updateThreadStatus(threadName, "生成答案", running = true)
        val answerCtx = answerContextFactory(config.copy(questionsMetadata = submitQuestions.associateBy { it.num }), state, threadName)
        val builder = SurveyAnswerBuilder(answerCtx)
        val actions = builder.buildPlan().actions
        if (actions.isEmpty()) return SubmitResult.Failure("未生成任何答案")

        state.discardPendingDistribution(threadName)
        for (action in actions) {
            for ((optionIndex, optionCount, rowIndex) in action.pendingDistributionChoices) {
                val statKey = if (rowIndex == null) "q:${action.configQuestionNum}" else "matrix:${action.configQuestionNum}:$rowIndex"
                state.appendPendingDistribution(threadName, statKey, optionIndex, optionCount)
            }
        }

        val durationSeconds = AnswerDurationSampler.sampleSeconds(config).toDouble()
        if (!config.submitEnabled) {
            state.commitPendingDistribution(threadName)
            return SubmitResult.Success
        }

        // 初始化获取 answerToken
        state.updateThreadStatus(threadName, "提交问卷", running = true)
        val timeCode = CredamoApi.newTimeCode()
        val init = try {
            initAnswer(origin, shortUrl, timeCode, ua, proxyAddress)
        } catch (e: CredamoUnavailableException) {
            return SubmitResult.ProviderUnavailable(e.message ?: "见数当前不可填写")
        } ?: return SubmitResult.Failure("初始化失败")
        val (answerToken, initTimestamp) = init

        val startedAt = sampleStartTimeMs(config, initTimestamp, durationSeconds)
        val body = buildSubmitBody(shortUrl, rawByNum, actions, answerCtx.config, startedAt, durationSeconds)

        val saveUrl = "${origin.trimEnd('/')}/v1/survey/answer/noauth/save?timeCode=$timeCode&answerToken=$answerToken"
        val headers = CredamoApi.requestHeaders(origin, shortUrl, ua, answerToken, jsonBody = true)
        if (state.stopRequested()) return SubmitResult.Cancelled
        val resp = http.postBody(saveUrl, body.toString(), headers = headers, timeoutSeconds = timeoutSeconds, proxyAddress = proxyAddress, surveySubmission = true)
        val payload = try {
            JSONObject(resp.body.ifBlank { "{}" })
        } catch (e: Exception) {
            state.discardPendingDistribution(threadName)
            return SubmitResult.Failure("提交返回非 JSON：${resp.body.take(120)}")
        }
        return if (CredamoApi.classifyOk(payload)) {
            state.commitPendingDistribution(threadName)
            SubmitResult.Success
        } else {
            state.discardPendingDistribution(threadName)
            val msg = payload.optString("message").ifBlank { payload.optString("msg").ifBlank { payload.optString("code") } }
            ProviderAvailability.unavailableMessage("见数", payload)?.let {
                return SubmitResult.ProviderUnavailable(it)
            }
            SubmitResult.Failure("见数提交失败：$msg")
        }
    }

    private suspend fun fetchDetail(origin: String, shortUrl: String, headers: Map<String, String>, proxy: String? = null): JSONObject {
        val resp = http.get("${origin.trimEnd('/')}/v1/survey/noauth/detail/get/$shortUrl", headers, timeoutSeconds, proxy)
        val payload = JSONObject(resp.body.ifBlank { "{}" })
        if (!CredamoApi.classifyOk(payload)) {
            ProviderAvailability.unavailableMessage("见数", payload)?.let { throw CredamoUnavailableException(it) }
            error("见数详情失败：${payload.optString("message")}")
        }
        return payload.optJSONObject("data") ?: payload
    }

    private suspend fun initAnswer(origin: String, shortUrl: String, timeCode: String, ua: String, proxy: String?): Pair<String, Long>? {
        val url = "${origin.trimEnd('/')}/v1/survey/answer/noauth/init/$shortUrl" +
            "?timeCode=$timeCode&accountCode=CDM&resolution=${CredamoApi.RESOLUTION}"
        val resp = http.get(url, CredamoApi.requestHeaders(origin, shortUrl, ua), timeoutSeconds, proxy)
        val payload = JSONObject(resp.body.ifBlank { "{}" })
        if (!CredamoApi.classifyOk(payload)) {
            ProviderAvailability.unavailableMessage("见数", payload)?.let { throw CredamoUnavailableException(it) }
            return null
        }
        val data = payload.optJSONObject("data") ?: payload
        val token = data.optString("answerToken").trim()
        if (token.isEmpty()) return null
        val ts = data.optLong("timestamp", System.currentTimeMillis())
        return token to ts
    }

    internal fun mergeSubmitQuestions(config: ExecutionConfig, rawByNum: Map<Int, JSONObject>): List<SurveyQuestionMeta> {
        val questions = config.questionsMetadata.values
            .filter { it.num > 0 && !it.isDescription }
            .sortedWith(compareBy({ it.page }, { it.num }))
        return questions.map { question ->
            val raw = rawByNum[question.num]
                ?: error("见数第${question.num}题未在接口详情中找到，无法纯 HTTP 提交")
            enrichQuestionMeta(question, raw)
        }
    }

    private fun enrichQuestionMeta(question: SurveyQuestionMeta, raw: JSONObject): SurveyQuestionMeta {
        val runtimeType = CredamoApi.rawProviderType(raw)
        return question.copy(
            options = CredamoApi.rawOptionCount(raw).takeIf { it > 0 } ?: question.options,
            rows = CredamoApi.rawRowCount(raw).takeIf { it > 0 } ?: question.rows,
            providerQuestionId = raw.optString("questionId")
                .ifBlank { raw.optString("qstId") }
                .ifBlank { question.providerQuestionId },
            providerType = runtimeType.ifBlank { question.providerType },
        )
    }

    private fun buildSubmitBody(
        shortUrl: String,
        rawByNum: Map<Int, JSONObject>,
        actions: List<AnswerAction>,
        config: ExecutionConfig,
        startedAt: Long,
        durationSeconds: Double,
    ): JSONObject {
        val durationMs = maxOf(1, (durationSeconds * 1000).roundToInt())
        val endedAt = startedAt + durationMs
        val perQuestionTime = if (actions.isNotEmpty()) maxOf(1, (durationMs / actions.size)) else 0

        val items = mutableListOf<Pair<Int, JSONObject>>()
        for (action in actions) {
            val raw = rawByNum[action.questionNum] ?: error("见数第${action.questionNum}题缺少接口题目数据")
            val item = encodeAnswer(raw, action, config)
            item.put("answerTime", perQuestionTime)
            val sortNo = raw.optInt("sortNo", items.size + 1)
            items.add(sortNo to item)
        }
        items.sortBy { it.first }
        val list = JSONArray()
        items.forEach { list.put(it.second) }

        return JSONObject()
            .put("answerStartTime", startedAt)
            .put("answerEndTime", endedAt)
            .put("status", 1)
            .put("answerQstList", list)
            .put("shortUrl", shortUrl)
            .put("resolution", CredamoApi.RESOLUTION)
            .put("sourceDetail", 1)
    }

    internal fun encodeAnswer(raw: JSONObject, action: AnswerAction, config: ExecutionConfig? = null): JSONObject {
        val questionType = CredamoApi.rawQuestionType(raw)
        val qstId = CredamoSubmitCodec.requiredIdFrom(raw, action.questionNum, "题目", "qstId", "id")
        val forcedOptionText = config?.questionsMetadata?.get(action.configQuestionNum)?.forcedOptionText
        return when {
            questionType == 1 || action.kind in listOf("text", "multi_text") -> {
                val text = action.textValues.filter { it.isNotBlank() }.joinToString("\n")
                if (text.isBlank()) error("见数第${action.questionNum}题没有生成填空答案")
                JSONObject().put("qstId", qstId).put("answerTime", 0).put("answerContent", text)
            }
            questionType == 2 || action.kind in listOf("single", "multiple", "select") ->
                CredamoSubmitCodec.encodeChoice(raw, action, qstId, forcedOptionText)
            questionType == 11 || action.kind == "scale" -> encodeScale(raw, action, qstId, forcedOptionText)
            questionType == 4 || questionType == 25 || action.kind == "matrix" -> encodeMatrix(raw, action, qstId)
            questionType == 6 || action.kind == "order" -> encodeOrder(raw, action, qstId)
            else -> error("见数第${action.questionNum}题类型暂不支持：$questionType")
        }
    }

    private fun encodeScale(raw: JSONObject, action: AnswerAction, qstId: Any, forcedOptionText: String?): JSONObject {
        val choices = CredamoSubmitCodec.choices(raw)
        val idx = CredamoSubmitCodec.choiceIndexByText(choices, forcedOptionText)
            ?: CredamoSubmitCodec.selectedChoiceIndex(action, "量表")
        val choice = CredamoSubmitCodec.selectedItem(choices, idx, action.questionNum, "选项")
        val item = JSONObject().put("qstId", qstId).put("answerTime", 0)
        item.put("answerQstChoice", CredamoSubmitCodec.choicePayload(choice, idx, action))
        return item
    }

    private fun encodeMatrix(raw: JSONObject, action: AnswerAction, qstId: Any): JSONObject {
        val rows = CredamoSubmitCodec.choices(raw)
        val columns = CredamoSubmitCodec.answers(raw)
        if (rows.isEmpty() || columns.isEmpty()) error("见数第${action.questionNum}题没有可提交的矩阵列")
        val answerRows = JSONArray()
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex >= action.matrixIndices.size) error("见数第${action.questionNum}题第${rowIndex + 1}行没有生成矩阵答案")
            val selectedCol = action.matrixIndices[rowIndex]
            val column = CredamoSubmitCodec.selectedItem(columns, selectedCol, action.questionNum, "矩阵列")
            val choiceAnswerList = JSONArray().put(
                JSONObject().put("answerId", CredamoSubmitCodec.requiredIdFrom(column, action.questionNum, "矩阵列", "answerId", "id")),
            )
            answerRows.put(
                JSONObject()
                    .put("choiceId", CredamoSubmitCodec.requiredIdFrom(row, action.questionNum, "矩阵行", "choiceId", "id"))
                    .put("choiceAnswerList", choiceAnswerList),
            )
        }
        if (answerRows.length() == 0) error("见数第${action.questionNum}题没有生成矩阵答案")
        return JSONObject().put("qstId", qstId).put("answerTime", 0).put("answerContent", "")
            .put("answerQstChoiceList", answerRows)
    }

    private fun encodeOrder(raw: JSONObject, action: AnswerAction, qstId: Any): JSONObject {
        val choices = CredamoSubmitCodec.choices(raw)
        val ranked = JSONArray()
        action.selectedIndices.forEachIndexed { rank, selectedIndex ->
            val choice = CredamoSubmitCodec.selectedItem(choices, selectedIndex, action.questionNum, "排序选项")
            ranked.put(
                JSONObject()
                    .put("choiceId", CredamoSubmitCodec.requiredIdFrom(choice, action.questionNum, "排序选项", "choiceId", "id"))
                    .put("choiceContent", rank + 1),
            )
        }
        if (ranked.length() == 0) error("见数第${action.questionNum}题没有生成排序答案")
        return JSONObject().put("qstId", qstId).put("answerTime", 0).put("answerContent", "")
            .put("answerChoiceContent", ranked)
    }
    private fun sampleStartTimeMs(config: ExecutionConfig, initStartedAtMs: Long, durationSeconds: Double): Long {
        val window = config.answerDatetimeWindowMs
        val startMs = window.first
        val endMs = window.last
        if (startMs <= 0 || endMs <= startMs) return initStartedAtMs
        val durationMs = maxOf(1, (durationSeconds * 1000).roundToInt())
        val latest = endMs - durationMs
        return if (latest <= startMs) startMs else Random.nextLong(startMs, latest + 1)
    }
}

private class CredamoUnavailableException(message: String) : RuntimeException(message)
