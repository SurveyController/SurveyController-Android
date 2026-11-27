package com.surveycontroller.android.data

import com.surveycontroller.android.provider.SurveyProviderType
import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.model.QuestionEntryType
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.psychometrics.OrdinalOptions
import com.surveycontroller.android.core.psychometrics.PsychoMath
import com.surveycontroller.android.core.questions.AnswerRule
import com.surveycontroller.android.core.questions.TextValues
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 将配置草稿编译为运行时 ExecutionConfig（构建概率数组与题号→配置索引映射）。
 * 对应桌面端配置向导生成 ExecutionConfig 的过程。
 */
object ConfigCompiler {
    private const val MaxThreads = 16
    private const val DimensionUngrouped = "未分组"
    private const val GlobalReliabilityDimension = "__global_reliability__"
    private const val DeepSeekBaseUrl = "https://api.deepseek.com/v1"
    private const val DeepSeekDefaultModel = "deepseek-v4-flash"
    private val validProxySources = setOf("default", "benefit", "custom")
    private val reliabilitySupportedTypes = setOf(
        QuestionEntryType.SINGLE,
        QuestionEntryType.DROPDOWN,
        QuestionEntryType.SCALE,
        QuestionEntryType.SCORE,
        QuestionEntryType.MATRIX,
    )

    data class AiOptions(
        val enabled: Boolean = false,
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
        val apiProtocol: String = "auto",
        val systemPrompt: String = "",
        val submissionReportEnabled: Boolean = true,
    )

    fun resolveAiOptions(draft: SurveyConfigDraft, local: AiOptions = AiOptions()): Pair<RunParamsDraft, AiOptions> {
        if (local.enabled) {
            return draft.params.copy(aiMode = "provider") to local
        }
        val imported = draft.preserved.importedAiConfig
        if (imported.present && imported.isProviderMode) {
            val provider = imported.provider.trim().lowercase().ifBlank { "deepseek" }
            return draft.params.copy(aiMode = "provider") to local.copy(
                enabled = true,
                baseUrl = imported.baseUrl.ifBlank { if (provider == "deepseek") DeepSeekBaseUrl else "" },
                apiKey = imported.apiKey,
                model = imported.model.ifBlank { if (provider == "deepseek") DeepSeekDefaultModel else "" },
                apiProtocol = normalizeAiApiProtocol(imported.apiProtocol),
                systemPrompt = imported.systemPrompt,
            )
        }
        return draft.params.copy(aiMode = "provider") to local.copy(enabled = false, apiProtocol = normalizeAiApiProtocol(local.apiProtocol))
    }

    private fun normalizeAiApiProtocol(raw: String?): String =
        when (raw?.trim()?.lowercase()) {
            "responses" -> "responses"
            "chat_completions" -> "chat_completions"
            else -> "auto"
        }

    /** 把填空模式解析为答案构建器可识别的令牌列表。custom 模式用用户输入的候选文本。 */
    private fun resolveTextCandidates(q: QuestionConfigDraft): List<String> = when (q.textMode) {
        "name" -> listOf("__RANDOM_NAME__")
        "mobile" -> listOf("__RANDOM_MOBILE__")
        "id_card" -> listOf("__RANDOM_ID_CARD__")
        "generic" -> listOf("__RANDOM_TEXT__")
        "integer" -> {
            val lo = minOf(q.textIntMin, q.textIntMax)
            val hi = maxOf(q.textIntMin, q.textIntMax)
            listOf("__RANDOM_INT__:$lo:$hi")
        }
        else -> q.textCandidates.toList().ifEmpty { listOf("已填写") }
    }

    private fun resolveTextProbabilities(q: QuestionConfigDraft, candidates: List<String>): List<Double> {
        if (candidates.isEmpty()) return listOf(1.0)
        if (q.textMode != "custom") return List(candidates.size) { 1.0 / candidates.size }
        val weights = q.optionWeights.take(candidates.size)
        return if (weights.size == candidates.size) normalizeProbabilities(weights, candidates.size)
        else List(candidates.size) { 1.0 / candidates.size }
    }

    private fun optionFillTexts(q: QuestionConfigDraft): List<String?>? {
        if (q.fillableOptionIndices.isEmpty()) return null
        val count = maxOf(q.optionTexts.size, q.optionFillTexts.size)
        if (count <= 0) return null
        val allowed = q.fillableOptionIndices.toSet()
        val values = MutableList<String?>(count) { null }
        for (idx in allowed) {
            if (idx !in 0 until count) continue
            val text = q.optionFillTexts.getOrNull(idx)?.trim().orEmpty()
            if (text.isNotEmpty()) values[idx] = text
        }
        return if (values.any { !it.isNullOrBlank() }) values else null
    }

    private fun blankMode(mode: String): String = when (mode.trim().lowercase()) {
        "name", TextValues.MODE_RANDOM_NAME -> TextValues.MODE_RANDOM_NAME
        "mobile", TextValues.MODE_RANDOM_MOBILE -> TextValues.MODE_RANDOM_MOBILE
        "id_card", TextValues.MODE_RANDOM_ID_CARD -> TextValues.MODE_RANDOM_ID_CARD
        "integer", TextValues.MODE_RANDOM_INTEGER -> TextValues.MODE_RANDOM_INTEGER
        else -> "custom"
    }

    private fun blankCount(q: QuestionConfigDraft): Int =
        maxOf(1, q.textInputLabels.size, q.multiTextBlankModes.size, q.multiTextBlankAiFlags.size, q.multiTextBlankIntRanges.size)

    private fun blankModes(q: QuestionConfigDraft): List<String> =
        (0 until blankCount(q)).map { idx -> blankMode(q.multiTextBlankModes.getOrNull(idx) ?: "custom") }

    private fun blankAiFlags(q: QuestionConfigDraft): List<Boolean> =
        (0 until blankCount(q)).map { idx -> q.multiTextBlankAiFlags.getOrNull(idx) == true }

    private fun blankRanges(q: QuestionConfigDraft): List<List<Int>> =
        (0 until blankCount(q)).map { idx ->
            val range = q.multiTextBlankIntRanges.getOrNull(idx)
            val lo = range?.getOrNull(0) ?: 0
            val hi = range?.getOrNull(1) ?: 100
            listOf(minOf(lo, hi), maxOf(lo, hi))
        }

    private fun normalizeIntRange(low: Int, high: Int, minValue: Int = 0, maxValue: Int = Int.MAX_VALUE): IntRange {
        val lo = low.coerceIn(minValue, maxValue)
        val hi = high.coerceIn(lo, maxValue)
        return lo..hi
    }

    private fun normalizeUserAgentRatios(raw: Map<String, Int>): Map<String, Int> {
        val keys = listOf("wechat", "mobile", "pc")
        val values = keys.map { key -> (raw[key] ?: 0).coerceIn(0, 100) }
        val total = values.sum()
        if (total != 100) return mapOf("wechat" to 33, "mobile" to 33, "pc" to 34)
        return keys.zip(values).toMap()
    }

    private fun normalizeProbabilities(raw: List<Double>, optionCount: Int): List<Double> {
        if (optionCount <= 0) return emptyList()
        val padded = MutableList(optionCount) { idx ->
            raw.getOrNull(idx)?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        }
        val total = padded.sum()
        if (total <= 0.0) return List(optionCount) { 1.0 / optionCount }
        return padded.map { it / total }
    }

    private fun singleLikeProb(q: QuestionConfigDraft, meta: SurveyQuestionMeta, usesConfiguredWeights: Boolean): Any =
        if (usesConfiguredWeights) normalizeProbabilities(q.optionWeights, singleLikeOptionCount(q, meta)) else -1

    private fun matrixRowProb(row: List<Double>, optionCount: Int, usesConfiguredWeights: Boolean): Any =
        if (usesConfiguredWeights) normalizeProbabilities(row, optionCount) else -1

    private fun singleLikeOptionCount(q: QuestionConfigDraft, meta: SurveyQuestionMeta): Int =
        maxOf(1, q.optionTexts.size.takeIf { it > 0 } ?: meta.options)

    private fun matrixOptionCount(q: QuestionConfigDraft, meta: SurveyQuestionMeta): Int =
        maxOf(1, q.optionTexts.size.takeIf { it > 0 } ?: meta.options)

    private val datetimeFormats = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
    )

    private fun parseDateTimeMs(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        for (format in datetimeFormats) {
            try {
                return LocalDateTime.parse(text, format).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    private fun parseAnswerDatetimeWindow(start: String, end: String): LongRange {
        val startMs = parseDateTimeMs(start) ?: return 0L..0L
        val endMs = parseDateTimeMs(end) ?: return 0L..0L
        return if (endMs > startMs) startMs..endMs else 0L..0L
    }

    private fun normalizePsychoBias(raw: String?): String {
        val value = raw?.trim()?.lowercase().orEmpty()
        return if (value in setOf("left", "center", "right", "custom")) value else "custom"
    }

    private fun normalizeProxySource(raw: String?): String {
        val value = raw?.trim()?.lowercase().orEmpty().ifBlank { "default" }
        return if (value in validProxySources) value else "default"
    }

    private fun normalizeDimension(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        return value.takeIf { it.isNotEmpty() && it != DimensionUngrouped }
    }

    private fun supportsJointReliability(q: QuestionConfigDraft, meta: SurveyQuestionMeta): Boolean {
        if (q.entryType !in reliabilitySupportedTypes) return false
        if (q.entryType != QuestionEntryType.SINGLE) return true
        val optionCount = maxOf(1, q.optionTexts.size.takeIf { it > 0 } ?: meta.options)
        return OrdinalOptions.infer(meta.optionTexts.ifEmpty { q.optionTexts })?.optionCount == optionCount
    }

    private fun matrixPsychoBias(q: QuestionConfigDraft): List<String> {
        val rowCount = maxOf(1, q.rowTexts.size, q.matrixRowWeights.size)
        val source = q.matrixBiasPresets.ifEmpty { mutableListOf(q.biasPreset) }
        return List(rowCount) { idx ->
            normalizePsychoBias(source.getOrElse(idx) { source.lastOrNull() ?: q.biasPreset })
        }
    }

    fun compile(draft: SurveyConfigDraft, ai: AiOptions = AiOptions()): ExecutionConfig {
        val singleProb = mutableListOf<Any?>()
        val droplistProb = mutableListOf<Any?>()
        val scaleProb = mutableListOf<Any?>()
        val multipleProb = mutableListOf<List<Double>>()
        val matrixProb = mutableListOf<Any?>()
        val sliderTargets = mutableListOf<Double>()
        val texts = mutableListOf<List<String>>()
        val textsProb = mutableListOf<List<Double>>()
        val textEntryTypes = mutableListOf<String>()
        val textAiFlags = mutableListOf<Boolean>()
        val textTitles = mutableListOf<String>()
        val multiTextBlankModes = mutableListOf<List<String>>()
        val multiTextBlankAiFlags = mutableListOf<List<Boolean>>()
        val multiTextBlankIntRanges = mutableListOf<List<List<Int>>>()
        val singleOptionFillTexts = mutableListOf<List<String?>?>()
        val singleAttachedOptionSelects = mutableListOf<List<Map<String, Any?>>>()
        val droplistOptionFillTexts = mutableListOf<List<String?>?>()
        val multipleOptionFillTexts = mutableListOf<List<String?>?>()
        val multipleAttachedOptionSelects = mutableListOf<List<Map<String, Any?>>>()
        val locationParts = HashMap<Int, List<String>>()

        val configIndexMap = HashMap<Int, Pair<String, Int>>()
        val providerConfigIndexMap = HashMap<String, Pair<String, Int>>()
        val providerConfigNumMap = HashMap<String, Int>()
        val dimensionMap = HashMap<Int, String?>()
        val ordinalScoreMap = HashMap<Int, List<Int>>()
        val strictMap = HashMap<Int, Boolean>()
        val psychoBiasMap = HashMap<Int, Any?>()
        val metas = HashMap<Int, SurveyQuestionMeta>()
        val reliabilityCandidates = mutableListOf<Triple<Int, Boolean, QuestionEntryType>>()

        // 各题型计数器（配置索引）
        var singleIdx = 0
        var droplistIdx = 0
        var scaleIdx = 0
        var multipleIdx = 0
        var matrixIdx = 0
        var sliderIdx = 0
        var textIdx = 0

        val metaByNum = draft.definition.questions.associateBy { it.num }

        for (q in draft.questions) {
            val meta = metaByNum[q.num] ?: continue
            metas[q.num] = meta
            // 自定义配比模式 → 自动严格比例（运行时分布矫正收敛到设定比例），对齐 is_strict_custom_ratio_mode
            val mode = q.distributionMode.trim().lowercase()
            val isCustom = mode == "custom"
            val usesConfiguredWeights = isCustom || mode == "weighted"
            strictMap[q.num] = isCustom
            val explicitDimension = normalizeDimension(q.dimension)
            val reliabilityCandidate = draft.params.reliabilityModeEnabled && supportsJointReliability(q, meta)
            val runtimeDimension = if (reliabilityCandidate) explicitDimension else null
            dimensionMap[q.num] = runtimeDimension
            if (reliabilityCandidate) {
                reliabilityCandidates.add(Triple(q.num, isCustom, q.entryType))
            }
            fun rememberProviderMapping(mappedValue: Pair<String, Int>) {
                val providerKey = ExecutionConfig.providerQuestionKey(
                    q.surveyProvider.ifBlank { meta.provider.ifBlank { draft.definition.provider.id } },
                    q.providerPageId.ifBlank { meta.providerPageId },
                    q.providerQuestionId.ifBlank { meta.providerQuestionId },
                )
                if (providerKey.isNotEmpty()) {
                    providerConfigIndexMap[providerKey] = mappedValue
                    providerConfigNumMap[providerKey] = q.num
                }
            }
            when (q.entryType) {
                QuestionEntryType.SINGLE -> {
                    val mappedValue = "single" to singleIdx++
                    configIndexMap[q.num] = mappedValue
                    rememberProviderMapping(mappedValue)
                    psychoBiasMap[q.num] = normalizePsychoBias(q.biasPreset)
                    // 完全随机 → -1（等概率随机）；自定义 → 权重
                    singleProb.add(singleLikeProb(q, meta, usesConfiguredWeights))
                    singleOptionFillTexts.add(optionFillTexts(q))
                    singleAttachedOptionSelects.add(q.attachedOptionSelects)
                    val optionCount = singleLikeOptionCount(q, meta)
                    OrdinalOptions.infer(meta.optionTexts)?.takeIf { it.optionCount == optionCount }?.let {
                        ordinalScoreMap[q.num] = it.scoreByChoiceIndex
                    }
                }
                QuestionEntryType.DROPDOWN -> {
                    val mappedValue = "dropdown" to droplistIdx++
                    configIndexMap[q.num] = mappedValue
                    rememberProviderMapping(mappedValue)
                    psychoBiasMap[q.num] = normalizePsychoBias(q.biasPreset)
                    droplistProb.add(singleLikeProb(q, meta, usesConfiguredWeights))
                    droplistOptionFillTexts.add(optionFillTexts(q))
                }
                QuestionEntryType.SCALE, QuestionEntryType.SCORE -> {
                    val key = if (q.entryType == QuestionEntryType.SCORE) "score" else "scale"
                    val mappedValue = key to scaleIdx++
                    configIndexMap[q.num] = mappedValue
                    rememberProviderMapping(mappedValue)
                    psychoBiasMap[q.num] = normalizePsychoBias(q.biasPreset)
                    scaleProb.add(singleLikeProb(q, meta, usesConfiguredWeights))
                }
                QuestionEntryType.MULTIPLE -> {
                    val mappedValue = "multiple" to multipleIdx++
                    configIndexMap[q.num] = mappedValue
                    rememberProviderMapping(mappedValue)
                    multipleProb.add(if (q.multiRandomCount) listOf(-1.0) else q.multiProbabilities.toList())
                    multipleOptionFillTexts.add(optionFillTexts(q))
                    multipleAttachedOptionSelects.add(q.attachedOptionSelects)
                }
                QuestionEntryType.MATRIX -> {
                    val mappedValue = "matrix" to matrixIdx
                    configIndexMap[q.num] = mappedValue
                    rememberProviderMapping(mappedValue)
                    psychoBiasMap[q.num] = matrixPsychoBias(q)
                    // 矩阵按行展开，每行一个配置索引
                    val optionCount = matrixOptionCount(q, meta)
                    for (row in q.matrixRowWeights) {
                        matrixProb.add(matrixRowProb(row, optionCount, usesConfiguredWeights))
                        matrixIdx++
                    }
                }
                QuestionEntryType.SLIDER -> {
                    val mappedValue = "slider" to sliderIdx++
                    configIndexMap[q.num] = mappedValue
                    rememberProviderMapping(mappedValue)
                    sliderTargets.add(if (isCustom) q.sliderTarget else Double.NaN)
                }
                QuestionEntryType.TEXT, QuestionEntryType.MULTI_TEXT -> {
                    val mappedValue = (if (q.entryType == QuestionEntryType.MULTI_TEXT) "multi_text" else "text") to textIdx++
                    configIndexMap[q.num] = mappedValue
                    rememberProviderMapping(mappedValue)
                    val resolved = resolveTextCandidates(q)
                    texts.add(resolved)
                    textsProb.add(resolveTextProbabilities(q, resolved))
                    textEntryTypes.add(if (q.entryType == QuestionEntryType.MULTI_TEXT) "multi_text" else "text")
                    textAiFlags.add(q.useAiText)
                    textTitles.add(q.title)
                    multiTextBlankModes.add(blankModes(q))
                    multiTextBlankAiFlags.add(blankAiFlags(q))
                    multiTextBlankIntRanges.add(blankRanges(q))
                }
                QuestionEntryType.ORDER -> {
                    val mappedValue = "order" to 0
                    configIndexMap[q.num] = mappedValue
                    rememberProviderMapping(mappedValue)
                }
                QuestionEntryType.LOCATION -> {
                    val mappedValue = "location" to -1
                    configIndexMap[q.num] = mappedValue
                    rememberProviderMapping(mappedValue)
                    locationParts[q.num] = q.locationParts.map { it.trim() }.take(3)
                }
            }
        }

        if (draft.params.reliabilityModeEnabled && reliabilityCandidates.isNotEmpty()) {
            val hasExplicitRuntimeDimension = dimensionMap.values.any { !it.isNullOrBlank() }
            if (!hasExplicitRuntimeDimension) {
                for ((questionNum, strictRatio, entryType) in reliabilityCandidates) {
                    if (strictRatio && entryType !in reliabilitySupportedTypes) continue
                    if (!dimensionMap[questionNum].isNullOrBlank()) continue
                    dimensionMap[questionNum] = GlobalReliabilityDimension
                }
            }
        }

        val p = draft.params
        return ExecutionConfig(
            url = SurveyProviderType.normalizeParseUrl(draft.definition.url),
            surveyTitle = draft.definition.title,
            surveyProvider = draft.definition.provider.id,
            singleProb = singleProb,
            droplistProb = droplistProb,
            scaleProb = scaleProb,
            multipleProb = multipleProb,
            matrixProb = matrixProb,
            sliderTargets = sliderTargets,
            texts = texts,
            textsProb = textsProb,
            textEntryTypes = textEntryTypes,
            textAiFlags = textAiFlags,
            textTitles = textTitles,
            locationParts = locationParts,
            multiTextBlankModes = multiTextBlankModes,
            multiTextBlankAiFlags = multiTextBlankAiFlags,
            multiTextBlankIntRanges = multiTextBlankIntRanges,
            singleOptionFillTexts = singleOptionFillTexts,
            singleAttachedOptionSelects = singleAttachedOptionSelects,
            droplistOptionFillTexts = droplistOptionFillTexts,
            multipleOptionFillTexts = multipleOptionFillTexts,
            optionFillCandidates = draft.questions.mapNotNull { question ->
                val options = question.optionFillCandidates
                    .filterKeys { it in question.fillableOptionIndices }
                    .mapValues { (_, choices) -> choices.map(String::trim).filter(String::isNotEmpty) }
                    .filterValues { it.isNotEmpty() }
                options.takeIf { it.isNotEmpty() }?.let { question.num to it }
            }.toMap(),
            multipleAttachedOptionSelects = multipleAttachedOptionSelects,
            questionConfigIndexMap = configIndexMap,
            providerQuestionConfigIndexMap = providerConfigIndexMap,
            providerQuestionConfigNumMap = providerConfigNumMap,
            questionDimensionMap = dimensionMap,
            questionOrdinalScoreMap = ordinalScoreMap,
            questionStrictRatioMap = strictMap,
            questionPsychoBiasMap = psychoBiasMap,
            questionsMetadata = metas,
            numThreads = p.numThreads.coerceIn(1, MaxThreads),
            targetNum = p.targetNum.coerceAtLeast(1),
            failThreshold = p.failThreshold.coerceAtLeast(1),
            stopOnFailEnabled = p.stopOnFailEnabled,
            psychoTargetAlpha = PsychoMath.normalizeTargetAlpha(p.psychoTargetAlpha),
            submitIntervalRangeSeconds = normalizeIntRange(p.submitIntervalMin, p.submitIntervalMax, 0, 300),
            answerDurationRangeSeconds = normalizeIntRange(p.answerDurationMin, p.answerDurationMax, 1, 30 * 60),
            answerDatetimeWindowMs = parseAnswerDatetimeWindow(p.answerDatetimeStart, p.answerDatetimeEnd),
            randomProxyIpEnabled = p.randomProxyIpEnabled,
            proxySource = normalizeProxySource(p.proxySource),
            proxyAreaCode = p.proxyAreaCode,
            customProxyApi = p.customProxyApi,
            randomUserAgentEnabled = p.randomUserAgentEnabled,
            userAgentRatios = normalizeUserAgentRatios(p.randomUserAgentRatios),
            reliabilityModeEnabled = p.reliabilityModeEnabled,
            submissionReportEnabled = ai.submissionReportEnabled,
            pauseOnAliyunCaptcha = p.pauseOnAliyunCaptcha,
            answerRules = AnswerRule.sanitizeRules(draft.answerRules, metas.values.toList()),
            aiMode = p.aiMode,
            aiEnabled = ai.enabled,
            aiBaseUrl = ai.baseUrl,
            aiApiKey = ai.apiKey,
            aiModel = ai.model,
            aiApiProtocol = normalizeAiApiProtocol(ai.apiProtocol),
            aiSystemPrompt = ai.systemPrompt,
        )
    }
}
