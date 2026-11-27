package com.surveycontroller.android.provider.tencent

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
import java.util.UUID

/**
 * 腾讯问卷适配器。复刻 tencent/provider/{parser,http_runtime}.py 的 API 链路。
 */
class TencentProvider(
    private val http: HttpClient,
    private val answerContextFactory: (ExecutionConfig, ExecutionState, String) -> AnswerContext =
        { c, s, t -> AnswerContext(c, s, t) },
) : SurveyProvider {

    override val type = SurveyProviderType.QQ
    private val fillBlankTokenRe = Regex("""\{(fillblank-[^{}]+)\}""", RegexOption.IGNORE_CASE)
    private val textLikeTypes = setOf("text", "textarea", "number")
    private val scoreTypes = setOf("star", "nps")
    private val singleChoiceTypes = setOf("radio", "select")

    override suspend fun parseSurvey(url: String): SurveyDefinition {
        val (surveyId, hash) = TencentApi.extractIdentifiers(url)
        val pageUrl = TencentApi.pageUrl(surveyId, hash)
        val headers = TencentApi.apiHeaders(pageUrl)
        TencentApi.ensureOk(TencentApi.request(http, surveyId, "session", hash, headers), "session")
        var lastErr: Exception? = null
        for (locale in TencentApi.LOCALES) {
            try {
                val meta = TencentApi.ensureOk(
                    TencentApi.request(http, surveyId, "meta", hash, headers, mapOf("locale" to locale)), "meta",
                )
                val qData = TencentApi.ensureOk(
                    TencentApi.request(http, surveyId, "questions", hash, headers, mapOf("locale" to locale)), "questions",
                )
                val questionsJson = qData.optJSONArray("questions") ?: continue
                val questions = TencentLogic.attach(TencentApi.standardize(questionsJson), questionsJson)
                if (questions.isNotEmpty()) {
                    return SurveyDefinition(SurveyProviderType.QQ, url, TencentApi.normalizeText(meta.optString("title")), questions)
                }
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: RuntimeException("腾讯问卷解析失败")
    }

    override suspend fun fillSurveyHttp(
        config: ExecutionConfig,
        state: ExecutionState,
        threadName: String,
        proxyAddress: String?,
        userAgent: String?,
    ): SubmitResult {
        val (surveyId, hash) = TencentApi.extractIdentifiers(config.url)
        val pageUrl = TencentApi.pageUrl(surveyId, hash)
        val ua = UserAgents.resolve(userAgent)
        val headers = TencentApi.apiHeaders(pageUrl, ua).toMutableMap()

        state.updateThreadStatus(threadName, "加载问卷", running = true)
        val sessionData = try {
            TencentApi.ensureOk(
                TencentApi.request(http, surveyId, "session", hash, headers, proxyAddress = proxyAddress), "session",
            )
        } catch (e: TencentUnavailableException) {
            return SubmitResult.ProviderUnavailable(e.message ?: "腾讯问卷当前不可填写")
        }
        val answerSessionId = sessionData.optString("answer_session_id").trim()
        if (answerSessionId.isNotEmpty()) headers["X-Answer-Session"] = answerSessionId

        val qData = try {
            TencentApi.ensureOk(
                TencentApi.request(http, surveyId, "questions", hash, headers, mapOf("locale" to "zhs"), proxyAddress), "questions",
            )
        } catch (e: TencentUnavailableException) {
            return SubmitResult.ProviderUnavailable(e.message ?: "腾讯问卷当前不可填写")
        }
        val rawQuestions = qData.optJSONArray("questions") ?: return SubmitResult.Failure("题目接口未返回数据")
        val rawById = HashMap<String, JSONObject>()
        for (i in 0 until rawQuestions.length()) {
            rawQuestions.optJSONObject(i)?.let { rawById[it.optString("id").trim()] = it }
        }
        val runtimeQuestions = TencentLogic.attach(TencentApi.standardize(rawQuestions), rawQuestions)
        val submitQuestions = mergeSubmitQuestions(config, runtimeQuestions)
        submitQuestions.firstOrNull { it.unsupported && !it.isDescription }?.let { q ->
            val reason = q.unsupportedReason.ifBlank { q.providerType.ifBlank { q.typeCode } }
            return SubmitResult.Failure("腾讯问卷第${q.num}题暂不支持：$reason")
        }

        state.updateThreadStatus(threadName, "生成答案", running = true)
        val answerCtx = answerContextFactory(config.copy(questionsMetadata = submitQuestions.associateBy { it.num }), state, threadName)
        val builder = SurveyAnswerBuilder(answerCtx)
        val plan = builder.buildPlan()
        val actionByQid = HashMap<String, AnswerAction>()
        for (action in plan.actions) {
            if (action.questionId.isNotEmpty()) actionByQid[action.questionId] = action
        }
        if (actionByQid.isEmpty()) return SubmitResult.Failure("未生成任何答案")

        // 登记待定分布
        state.discardPendingDistribution(threadName)
        for (action in actionByQid.values) {
            for ((optionIndex, optionCount, rowIndex) in action.pendingDistributionChoices) {
                val statKey = if (rowIndex == null) "q:${action.configQuestionNum}" else "matrix:${action.configQuestionNum}:$rowIndex"
                state.appendPendingDistribution(threadName, statKey, optionIndex, optionCount)
            }
        }

        // 按页聚合答案
        val pageQuestions = LinkedHashMap<String, JSONArray>()
        for (i in 0 until rawQuestions.length()) {
            val rawQ = rawQuestions.optJSONObject(i) ?: continue
            val qid = rawQ.optString("id").trim()
            val action = actionByQid[qid] ?: continue
            val pageId = rawQ.optString("page_id").trim()
            if (pageId.isEmpty()) continue
            val arr = pageQuestions.getOrPut(pageId) { JSONArray() }
            encodeAnswer(rawQ, action).forEach { arr.put(it) }
        }
        if (pageQuestions.isEmpty()) return SubmitResult.Failure("未生成可提交答案")

        if (!config.submitEnabled) {
            state.commitPendingDistribution(threadName)
            return SubmitResult.Success
        }

        val duration = AnswerDurationSampler.sampleSeconds(config)
        val pages = JSONArray()
        for ((pageId, qs) in pageQuestions) {
            pages.put(JSONObject().put("id", pageId).put("questions", qs))
        }
        val answerSurvey = JSONObject()
            .put("duration", duration)
            .put("ua", ua)
            .put("referrer", "")
            .put("uid", UUID.randomUUID().toString())
            .put("sid", UUID.randomUUID().toString())
            .put("openid", "")
            .put("latitude", JSONObject.NULL)
            .put("longitude", JSONObject.NULL)
            .put("is_update", false)
            .put("locale", "zhs")
            .put("pages", pages)
        val body = JSONObject()
            .put("survey_id", surveyId.toLong())
            .put("hash", hash)
            .put("answer_survey", answerSurvey)

        val submitHeaders = UserAgents.DEFAULT_HEADERS.toMutableMap().apply {
            this["User-Agent"] = ua
            this["Accept"] = "application/json, text/plain, */*"
            this["Content-Type"] = "application/json;charset=UTF-8"
            this["Origin"] = "https://wj.qq.com"
            this["Referer"] = pageUrl
            if (answerSessionId.isNotEmpty()) this["X-Answer-Session"] = answerSessionId
        }
        val submitUrl = buildString {
            append("https://wj.qq.com/api/v2/respondent/surveys/$surveyId/answers")
            append("?pv_uid=").append(UUID.randomUUID().toString())
            append("&hash=").append(hash)
            append("&_=").append(System.currentTimeMillis())
        }

        state.updateThreadStatus(threadName, "提交问卷", running = true)
        if (state.stopRequested()) return SubmitResult.Cancelled
        val resp = http.postBody(submitUrl, body.toString(), headers = submitHeaders, timeoutSeconds = 30, proxyAddress = proxyAddress, surveySubmission = true)
        val payload = try {
            JSONObject(resp.body.ifBlank { "{}" })
        } catch (e: Exception) {
            state.discardPendingDistribution(threadName)
            return SubmitResult.Failure("提交返回非 JSON：${resp.body.take(120)}")
        }
        val code = payload.optString("code").uppercase()
        if (code == "OK" || code == "0") {
            state.updateThreadStatus(threadName, "校验结果", running = true)
            return try {
                TencentSubmitVerifier.confirmPersisted(
                    answerSessionId = answerSessionId,
                    initialSessionData = sessionData,
                    submitPayload = payload,
                    fetchSessionData = {
                        TencentApi.ensureOk(
                            TencentApi.request(
                                http,
                                surveyId,
                                "session",
                                hash,
                                headers,
                                proxyAddress = proxyAddress,
                            ),
                            "session",
                        )
                    },
                )
                state.commitPendingDistribution(threadName)
                SubmitResult.Success
            } catch (e: TencentUnavailableException) {
                state.discardPendingDistribution(threadName)
                SubmitResult.ProviderUnavailable(e.message ?: "腾讯问卷当前不可填写")
            } catch (e: Exception) {
                state.discardPendingDistribution(threadName)
                SubmitResult.Failure(e.message ?: "腾讯问卷提交结果校验失败")
            }
        }
        state.discardPendingDistribution(threadName)
        val msg = payload.optString("message").ifBlank { payload.optString("msg").ifBlank { code } }
        ProviderAvailability.unavailableMessage("腾讯问卷", payload)?.let {
            return SubmitResult.ProviderUnavailable(it)
        }
        return SubmitResult.Failure("腾讯问卷提交失败：$msg")
    }

    internal fun mergeSubmitQuestions(
        config: ExecutionConfig,
        runtimeQuestions: List<SurveyQuestionMeta>,
    ): List<SurveyQuestionMeta> {
        val currentQuestions = runtimeQuestions.filter { it.num > 0 && !it.isDescription }
        val orderedExisting = config.questionsMetadata.values
            .filter { it.num > 0 && !it.isDescription }
            .sortedBy { it.num }
        val existingBySignature = HashMap<Pair<String, String>, ArrayDeque<SurveyQuestionMeta>>()
        for (question in orderedExisting) {
            val signature = questionSignature(question)
            if (signature.second.isNotEmpty()) {
                existingBySignature.getOrPut(signature) { ArrayDeque() }.addLast(question)
            }
        }
        return currentQuestions.mapIndexed { index, current ->
            val existing = matchExistingSubmitQuestion(current, existingBySignature, orderedExisting, index)
            if (existing != null) mergeSubmitQuestionMeta(existing, current) else current
        }
    }

    private fun mergeSubmitQuestionMeta(existing: SurveyQuestionMeta, current: SurveyQuestionMeta): SurveyQuestionMeta =
        current.copy(
            num = existing.num.takeIf { it > 0 } ?: current.num,
            displayNum = existing.displayNum,
            title = current.title.ifBlank { existing.title.trim() },
            description = current.description.ifBlank { existing.description.trim() },
            hasJump = existing.hasJump,
            jumpRules = existing.jumpRules,
            hasDisplayCondition = existing.hasDisplayCondition,
            displayConditions = existing.displayConditions,
            hasDependentDisplayLogic = existing.hasDependentDisplayLogic,
            controlsDisplayTargets = existing.controlsDisplayTargets,
            logicParseStatus = existing.logicParseStatus.ifBlank { current.logicParseStatus },
            questionMedia = existing.questionMedia.ifEmpty { current.questionMedia },
            required = existing.required,
        )

    private fun matchExistingSubmitQuestion(
        current: SurveyQuestionMeta,
        existingBySignature: MutableMap<Pair<String, String>, ArrayDeque<SurveyQuestionMeta>>,
        orderedExisting: List<SurveyQuestionMeta>,
        index: Int,
    ): SurveyQuestionMeta? {
        val signature = questionSignature(current)
        if (signature.second.isNotEmpty()) {
            val candidates = existingBySignature[signature]
            if (candidates != null && candidates.isNotEmpty()) return candidates.removeFirst()
        }
        val candidate = orderedExisting.getOrNull(index) ?: return null
        return if (questionTypesCompatible(current, candidate)) candidate else null
    }

    private fun questionSignature(question: SurveyQuestionMeta): Pair<String, String> {
        val providerType = question.providerType.trim().lowercase()
        val typeMarker = providerType.ifBlank { meaningfulTypeCode(question) }
        return typeMarker to normalizeMatchText(question.title)
    }

    private fun questionTypesCompatible(current: SurveyQuestionMeta, existing: SurveyQuestionMeta): Boolean {
        val currentProviderType = current.providerType.trim().lowercase()
        val existingProviderType = existing.providerType.trim().lowercase()
        if (currentProviderType.isNotEmpty() && existingProviderType.isNotEmpty()) {
            return currentProviderType == existingProviderType
        }
        val currentType = meaningfulTypeCode(current)
        val existingType = meaningfulTypeCode(existing)
        if (currentType.isNotEmpty() && existingType.isNotEmpty()) return currentType == existingType
        return true
    }

    private fun meaningfulTypeCode(question: SurveyQuestionMeta): String =
        question.typeCode.trim().takeUnless { it.isEmpty() || it == "0" }.orEmpty()

    private fun normalizeMatchText(value: String): String =
        value.filterNot { it.isWhitespace() }

    /** 按腾讯格式编码答案，可能产出多条（矩阵每行一条）。 */
    internal fun encodeAnswer(rawQ: JSONObject, action: AnswerAction): List<JSONObject> {
        val providerType = rawQ.optString("type").trim()
        val providerTypeKey = providerType.lowercase()
        val qid = requiredQuestionId(rawQ, action)
        return when {
            action.kind == "text" || providerTypeKey in textLikeTypes -> {
                val text = action.textValues.filter { it.isNotBlank() }.joinToString("\n")
                if (text.isBlank()) error("腾讯问卷第${action.questionNum}题没有生成填空答案")
                listOf(JSONObject().put("id", qid).put("type", providerType.ifEmpty { "text" }).put("text", text))
            }
            providerTypeKey in scoreTypes -> {
                val items = optionItems(rawQ)
                if (items.isEmpty()) error("腾讯问卷第${action.questionNum}题没有可提交的评分选项")
                val idx = (action.scalarValue ?: action.selectedIndices.firstOrNull() ?: -1)
                if (idx < 0 || idx >= items.size) error("腾讯问卷第${action.questionNum}题没有生成评分答案")
                val score = items[idx].optString("text")
                    .ifBlank { items[idx].optString("id") }
                    .ifBlank { idx.toString() }
                listOf(JSONObject().put("id", "$qid-$score").put("type", providerType).put("answer", score))
            }
            action.kind == "matrix" || providerTypeKey.startsWith("matrix_") -> encodeMatrix(rawQ, action)
            else -> listOf(encodeChoice(rawQ, action, qid, providerType, providerTypeKey))
        }
    }

    private fun requiredQuestionId(rawQ: JSONObject, action: AnswerAction): String {
        val qid = rawQ.optString("id").trim().ifEmpty { action.questionId.trim() }
        if (qid.isEmpty()) error("腾讯问卷第${action.questionNum}题缺少题目 id")
        return qid
    }

    private fun encodeChoice(
        rawQ: JSONObject,
        action: AnswerAction,
        qid: String,
        providerType: String,
        providerTypeKey: String,
    ): JSONObject {
        val items = optionItems(rawQ)
        if (items.isEmpty()) error("腾讯问卷第${action.questionNum}题没有可提交的选项")
        val selected = action.selectedIndices.distinct()
        if (selected.isEmpty()) error("腾讯问卷第${action.questionNum}题没有生成选项答案")
        val outOfRange = selected.firstOrNull { it !in items.indices }
        if (outOfRange != null) error("腾讯问卷第${action.questionNum}题第${outOfRange + 1}个选项越界")
        if (providerTypeKey in singleChoiceTypes && selected.size != 1) {
            error("腾讯问卷第${action.questionNum}题单选/下拉只能提交一个选项")
        }
        val selectedSet = selected.toSet()
        val options = JSONArray()
        items.forEachIndexed { index, opt ->
            val optionId = opt.optString("id").trim()
            if (optionId.isEmpty()) error("腾讯问卷第${action.questionNum}题第${index + 1}个选项缺少选项 id")
            options.put(
                JSONObject()
                    .put("id", optionId)
                    .put("text", opt.optString("text").trim())
                    .put("checked", if (index in selectedSet) 1 else 0),
            )
        }
        val blanks = encodeOptionFillBlanks(rawQ, action, items)
        return JSONObject().put("id", qid).put("type", providerType).put("blanks", blanks).put("options", options)
    }

    private fun encodeOptionFillBlanks(rawQ: JSONObject, action: AnswerAction, options: List<JSONObject>): JSONArray {
        val fillTexts = action.optionFillTexts
            .mapNotNull { (optionIndex, value) ->
                value.trim().takeIf { it.isNotEmpty() }?.let { optionIndex to it }
            }
            .toMap()
        if (fillTexts.isEmpty()) return JSONArray()

        val blankIdByOptionIndex = optionFillBlankIdsByOptionIndex(rawQ, options)
        val blanks = JSONArray()
        for ((optionIndex, value) in fillTexts) {
            if (optionIndex !in action.selectedIndices) continue
            val blankId = blankIdByOptionIndex[optionIndex]
                ?: error("腾讯问卷第${action.questionNum}题第${optionIndex + 1}个选项缺少填空 id，无法提交补充文本")
            blanks.put(JSONObject().put("id", blankId).put("value", value))
        }
        return blanks
    }

    private fun optionFillBlankIdsByOptionIndex(rawQ: JSONObject, options: List<JSONObject>): Map<Int, String> {
        val optionIdToIndex = options.mapIndexedNotNull { index, option ->
            option.optString("id").trim().takeIf { it.isNotEmpty() }?.let { it to index }
        }.toMap()
        val result = LinkedHashMap<Int, String>()
        val settings = rawQ.optJSONArray("blank_setting")
        if (settings != null) {
            for (i in 0 until settings.length()) {
                val blank = settings.optJSONObject(i) ?: continue
                val blankId = blank.optString("id").trim()
                val attachId = blank.optString("attach_id").trim()
                val type = blank.optString("type").trim().lowercase()
                if (blankId.isEmpty() || attachId.isEmpty()) continue
                if (type.isNotEmpty() && type != "option") continue
                val optionIndex = optionIdToIndex[attachId] ?: continue
                result.putIfAbsent(optionIndex, blankId)
            }
        }
        options.forEachIndexed { index, option ->
            if (result.containsKey(index)) return@forEachIndexed
            val token = fillBlankTokenRe.find(option.optString("text"))?.groupValues?.getOrNull(1)?.trim()
            if (!token.isNullOrEmpty()) result[index] = token
        }
        return result
    }

    internal fun encodeMatrix(rawQ: JSONObject, action: AnswerAction): List<JSONObject> {
        val rows = rawQ.optJSONArray("sub_titles")
        val qid = requiredQuestionId(rawQ, action)
        val providerType = rawQ.optString("type").trim()
        val template = optionItems(rawQ)
        if (rows == null || rows.length() == 0) error("腾讯问卷第${action.questionNum}题没有生成矩阵答案")
        if (template.isEmpty()) error("腾讯问卷第${action.questionNum}题没有可提交的矩阵列")
        val result = mutableListOf<JSONObject>()
        for (rowIndex in 0 until rows.length()) {
            val row = rows.optJSONObject(rowIndex) ?: continue
            val selectedIndex = action.matrixIndices.getOrElse(rowIndex) { -1 }
            if (selectedIndex < 0 || selectedIndex >= template.size) {
                error("腾讯问卷第${action.questionNum}题第${rowIndex + 1}行没有生成矩阵答案")
            }
            val selectedOption = template[selectedIndex]
            val rowId = row.optString("id").trim()
            val compositeId: String
            if (providerType == "matrix_radio") {
                if (rowId.isEmpty()) error("腾讯问卷第${action.questionNum}题第${rowIndex + 1}行缺少矩阵行 id")
                val optionId = selectedOption.optString("id").trim()
                if (optionId.isEmpty()) error("腾讯问卷第${action.questionNum}题第${rowIndex + 1}行缺少矩阵列 id")
                compositeId = "${qid}_${rowId}_$optionId"
            } else {
                if (rowId.isEmpty()) error("腾讯问卷第${action.questionNum}题第${rowIndex + 1}行缺少矩阵行 id")
                val score = selectedOption.optString("text").ifBlank { (selectedIndex + 1).toString() }
                compositeId = "$qid-$rowId-$score"
            }
            result.add(JSONObject().put("id", compositeId).put("type", providerType).put("answer", "on"))
        }
        if (result.isEmpty()) error("腾讯问卷第${action.questionNum}题没有生成矩阵答案")
        return result
    }

    private fun optionItems(rawQ: JSONObject): List<JSONObject> {
        rawQ.optJSONArray("options")?.let { arr ->
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }
        val providerType = rawQ.optString("type").trim()
        if (providerType !in listOf("star", "nps", "matrix_star")) return emptyList()
        val count = rawQ.optInt("star_num", 0)
        if (count <= 0) return emptyList()
        val start = if (rawQ.has("star_begin_num")) rawQ.optInt("star_begin_num")
        else if (providerType == "nps") 0 else 1
        return (0 until count).map { JSONObject().put("id", "").put("text", (start + it).toString()) }
    }
}
