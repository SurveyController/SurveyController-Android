package com.surveycontroller.android.data

import com.surveycontroller.android.core.model.QuestionEntryType
import com.surveycontroller.android.core.model.DisplayCondition
import com.surveycontroller.android.core.model.DisplayTarget
import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.model.JumpRule
import com.surveycontroller.android.core.model.QuestionMedia
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.questions.AnswerRule
import com.surveycontroller.android.core.questions.TextValues
import com.surveycontroller.android.provider.SurveyDefinition
import com.surveycontroller.android.provider.SurveyProviderType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 运行时配置序列化/反序列化。对齐桌面端 software/core/config/codec.py 的 schema v6 字段名，
 * 实现配置导出/导入与跨设备同步。
 */
object ConfigCodec {
    const val SCHEMA_VERSION = 6
    private const val DEFAULT_ANSWER_DURATION_MIN = 60
    private const val DEFAULT_ANSWER_DURATION_MAX = 120
    private const val MAX_ANSWER_DURATION_SECONDS = 30 * 60
    private val VALID_PROXY_SOURCES = setOf("default", "benefit", "custom")
    private val LEGACY_CONFIG_KEYS = listOf("random_proxy_api", "ai_enabled")
    private val VALID_LOGIC_PARSE_STATUSES = setOf("complete", "none", "unknown")
    private val TERMINATE_JUMP_KEYWORDS = listOf("结束作答", "结束答题", "结束填写", "终止作答", "停止作答")

    fun serialize(draft: SurveyConfigDraft): String {
        val def = draft.definition
        val p = draft.params
        val root = JSONObject()
        root.put("config_schema_version", SCHEMA_VERSION)
        root.put("url", def.url)
        root.put("survey_title", def.title)
        root.put("survey_provider", def.provider.id)
        root.put("target", p.targetNum)
        root.put("threads", p.numThreads)
        root.put("answer_duration", JSONArray().put(p.answerDurationMin).put(p.answerDurationMax))
        root.put("submit_interval", JSONArray().put(p.submitIntervalMin).put(p.submitIntervalMax))
        root.put("answer_datetime_window", JSONArray().put(p.answerDatetimeStart).put(p.answerDatetimeEnd))
        root.put("random_ip_enabled", p.randomProxyIpEnabled)
        root.put("proxy_source", normalizeProxySource(p.proxySource))
        root.put("custom_proxy_api", p.customProxyApi)
        p.proxyAreaCode?.let { root.put("proxy_area_code", it) }
        root.put("random_ua_enabled", p.randomUserAgentEnabled)
        root.put("random_ua_ratios", JSONObject(p.randomUserAgentRatios))
        root.put("fail_stop_enabled", p.stopOnFailEnabled)
        root.put("fail_threshold", p.failThreshold)
        root.put("reliability_mode_enabled", p.reliabilityModeEnabled)
        root.put("psycho_target_alpha", p.psychoTargetAlpha)
        root.put("pause_on_aliyun_captcha", p.pauseOnAliyunCaptcha)
        root.put("ai_mode", p.aiMode)
        val ai = draft.preserved.importedAiConfig
        if (ai.present) {
            root.put("ai_provider", ai.provider.ifBlank { "deepseek" })
            root.put("ai_api_key", ai.apiKey)
            root.put("ai_base_url", ai.baseUrl)
            root.put("ai_api_protocol", ai.apiProtocol.ifBlank { "auto" })
            root.put("ai_model", ai.model)
            root.put("ai_system_prompt", ai.systemPrompt)
        }
        root.put("reverse_fill_enabled", draft.preserved.reverseFillEnabled)
        root.put("reverse_fill_source_path", draft.preserved.reverseFillSourcePath)
        root.put("reverse_fill_format", draft.preserved.reverseFillFormat.ifBlank { "auto" })
        root.put("reverse_fill_start_row", draft.preserved.reverseFillStartRow.coerceAtLeast(1))
        root.put("reverse_fill_threads", draft.preserved.reverseFillThreads.coerceAtLeast(1))
        root.put("dimension_groups", JSONArray(draft.preserved.dimensionGroups))
        // 条件规则
        val answerRules = AnswerRule.sanitizeRules(draft.answerRules, def.questions)
        if (answerRules.isNotEmpty()) {
            val rules = JSONArray()
            for (r in answerRules) rules.put(JSONObject(r))
            root.put("answer_rules", rules)
        }

        // 题目结构（用于导入时无需重新解析）
        val questionsInfo = JSONArray()
        for (q in def.questions) questionsInfo.put(serializeMeta(q, def.provider.id))
        root.put("questions_info", questionsInfo)

        // 题目配置
        val entries = JSONArray()
        for (q in draft.questions) entries.put(serializeEntry(q, def.provider.id))
        root.put("question_entries", entries)
        return root.toString(2)
    }

    private fun serializeMeta(q: SurveyQuestionMeta, defaultProvider: String): JSONObject = JSONObject().apply {
        put("num", q.num)
        q.displayNum?.let { put("display_num", it) }
        put("title", q.title)
        put("description", q.description)
        put("type_code", q.typeCode)
        put("page", q.page)
        put("options", q.options)
        put("rows", q.rows)
        put("option_texts", JSONArray(q.optionTexts))
        put("row_texts", JSONArray(q.rowTexts))
        q.forcedOptionIndex?.let { put("forced_option_index", it) }
        q.forcedOptionText?.takeIf { it.isNotBlank() }?.let { put("forced_option_text", it) }
        put("forced_texts", JSONArray(q.forcedTexts))
        put("text_inputs", q.textInputs)
        put("text_input_labels", JSONArray(q.textInputLabels))
        put("fillable_options", JSONArray(q.fillableOptions))
        put("attached_option_selects", JSONArray(q.attachedOptionSelects.map { JSONObject(it) }))
        put("has_attached_option_select", q.hasAttachedOptionSelect)
        q.multiMinLimit?.let { put("multi_min_limit", it) }
        q.multiMaxLimit?.let { put("multi_max_limit", it) }
        q.sliderMin?.let { put("slider_min", it) }
        q.sliderMax?.let { put("slider_max", it) }
        q.sliderStep?.let { put("slider_step", it) }
        put("required", q.required)
        put("provider", normalizeProviderId(q.provider).ifBlank { defaultProvider })
        put("provider_question_id", q.providerQuestionId.trim())
        put("provider_page_id", q.providerPageId.trim())
        put("provider_type", q.providerType.trim())
        q.providerPageRaw?.let { put("provider_page_raw", JSONObject(it)) }
        put("is_location", q.isLocation)
        put("is_rating", q.isRating)
        put("rating_max", q.ratingMax)
        put("is_description", q.isDescription)
        put("is_text_like", q.isTextLike)
        put("is_multi_text", q.isMultiText)
        put("is_slider_matrix", q.isSliderMatrix)
        put("has_jump", q.hasJump)
        put("jump_rules", JSONArray(q.jumpRules.map { serializeJumpRule(it) }))
        put("has_display_condition", q.hasDisplayCondition)
        put("display_conditions", JSONArray(q.displayConditions.map { serializeDisplayCondition(it) }))
        put("has_dependent_display_logic", q.hasDependentDisplayLogic)
        put("controls_display_targets", JSONArray(q.controlsDisplayTargets.map { serializeDisplayTarget(it) }))
        put("logic_parse_status", normalizeLogicParseStatus(q.logicParseStatus))
        put("question_media", JSONArray(q.questionMedia.map { serializeQuestionMedia(it) }))
        put("unsupported", q.unsupported)
        put("unsupported_reason", q.unsupportedReason)
    }

    private fun serializeJumpRule(rule: JumpRule): JSONObject = JSONObject().apply {
        put("option_index", rule.optionIndex)
        rule.targetQuestion?.let { put("jumpto", it) }
        rule.optionText?.takeIf { it.isNotBlank() }?.let { put("option_text", it) }
        put("terminates_survey", rule.terminates)
    }

    private fun serializeDisplayCondition(condition: DisplayCondition): JSONObject = JSONObject().apply {
        put("condition_question_num", condition.conditionQuestionNum)
        put("condition_mode", condition.conditionMode)
        put("condition_option_indices", JSONArray(condition.conditionOptionIndices))
    }

    private fun serializeDisplayTarget(target: DisplayTarget): JSONObject = JSONObject().apply {
        put("target_question_num", target.targetQuestionNum)
        put("condition_mode", target.conditionMode)
        put("condition_option_indices", JSONArray(target.conditionOptionIndices))
    }

    private fun serializeQuestionMedia(media: QuestionMedia): JSONObject = JSONObject().apply {
        put("kind", media.kind)
        put("scope", media.scope)
        if (media.index == null) put("index", JSONObject.NULL) else put("index", media.index)
        put("source_url", media.sourceUrl)
        put("label", media.label)
    }

    private fun serializeTextCandidates(q: QuestionConfigDraft): List<String> {
        return when (q.entryType) {
            QuestionEntryType.TEXT -> {
                if (normalizeTextMode(q.textMode) == "generic") listOf("__RANDOM_TEXT__") else q.textCandidates
            }
            QuestionEntryType.MULTI_TEXT -> q.textCandidates.map { candidate ->
                TextValues.splitMultiTextCandidate(candidate).joinToString(TextValues.DESKTOP_MULTI_TEXT_DELIMITER)
            }
            else -> q.textCandidates
        }
    }

    private fun desktopTextRandomMode(q: QuestionConfigDraft): String =
        if (q.entryType == QuestionEntryType.TEXT) {
            when (normalizeTextMode(q.textMode)) {
                "name" -> "name"
                "mobile" -> "mobile"
                "id_card" -> "id_card"
                "integer" -> "integer"
                else -> "none"
            }
        } else {
            "none"
        }

    private fun desktopMultiTextBlankModes(q: QuestionConfigDraft): List<String> =
        q.multiTextBlankModes.map { mode ->
            when (normalizeTextMode(mode)) {
                "name" -> "name"
                "mobile" -> "mobile"
                "id_card" -> "id_card"
                "integer" -> "integer"
                else -> "none"
            }
        }

    private fun desktopProbabilityPayload(q: QuestionConfigDraft): Any {
        val mode = q.distributionMode.trim().lowercase()
        val customMode = mode == "custom"
        val weightedMode = mode == "weighted"
        return when (q.entryType) {
            QuestionEntryType.MULTIPLE ->
                JSONArray(if (q.multiRandomCount) List(desktopOptionCount(q)) { 50.0 } else q.multiProbabilities)
            QuestionEntryType.MATRIX ->
                if (customMode || weightedMode) JSONArray(q.matrixRowWeights.map { JSONArray(it) }) else -1
            QuestionEntryType.SLIDER ->
                if (customMode) JSONArray().put(q.sliderTarget) else -1
            QuestionEntryType.TEXT, QuestionEntryType.MULTI_TEXT ->
                JSONArray(q.optionWeights)
            else ->
                if (customMode || weightedMode) JSONArray(q.optionWeights) else -1
        }
    }

    private fun desktopCustomWeights(q: QuestionConfigDraft): Any? =
        when (q.entryType) {
            QuestionEntryType.MULTIPLE ->
                if (!q.multiRandomCount) JSONArray(q.multiProbabilities) else JSONArray(List(desktopOptionCount(q)) { 50.0 })
            QuestionEntryType.MATRIX ->
                JSONArray(q.matrixRowWeights.map { JSONArray(it) })
            QuestionEntryType.SLIDER ->
                JSONArray().put(q.sliderTarget)
            QuestionEntryType.TEXT, QuestionEntryType.MULTI_TEXT ->
                JSONArray(q.optionWeights)
            else ->
                JSONArray(q.optionWeights)
        }

    private fun desktopDistributionMode(q: QuestionConfigDraft): String =
        if (q.distributionMode.trim().equals("weighted", ignoreCase = true)) "random" else q.distributionMode

    private fun desktopRows(q: QuestionConfigDraft): Int =
        when (q.entryType) {
            QuestionEntryType.MATRIX -> maxOf(1, q.rowTexts.size, q.matrixRowWeights.size)
            else -> 1
        }

    private fun desktopOptionCount(q: QuestionConfigDraft): Int =
        when (q.entryType) {
            QuestionEntryType.MATRIX -> maxOf(1, q.optionTexts.size, q.matrixRowWeights.firstOrNull()?.size ?: 0)
            QuestionEntryType.MULTIPLE -> maxOf(1, q.optionTexts.size, q.multiProbabilities.size)
            QuestionEntryType.TEXT -> maxOf(1, q.textCandidates.size)
            QuestionEntryType.MULTI_TEXT -> maxOf(1, q.textInputLabels.size, TextValues.splitMultiTextCandidate(q.textCandidates.firstOrNull().orEmpty()).size)
            QuestionEntryType.SLIDER, QuestionEntryType.LOCATION -> 1
            else -> maxOf(1, q.optionTexts.size, q.optionWeights.size)
        }

    private fun serializeEntry(q: QuestionConfigDraft, defaultProvider: String): JSONObject = JSONObject().apply {
        put("question_num", q.num)
        put("question_title", q.title)
        put("question_type", entryTypeId(q.entryType))
        put("rows", desktopRows(q))
        put("option_count", desktopOptionCount(q))
        put("survey_provider", normalizeProviderId(q.surveyProvider).ifBlank { defaultProvider })
        put("provider_question_id", q.providerQuestionId)
        put("provider_page_id", q.providerPageId)
        put("probabilities", desktopProbabilityPayload(q))
        desktopCustomWeights(q)?.let { put("custom_weights", it) }
        put("multi_probabilities", JSONArray(q.multiProbabilities))
        put("multi_random_count", q.multiRandomCount)
        put("matrix_row_weights", JSONArray(q.matrixRowWeights.map { JSONArray(it) }))
        put("texts", JSONArray(serializeTextCandidates(q)))
        put("ai_enabled", q.useAiText)
        put("text_mode", q.textMode)
        put("text_int_min", q.textIntMin)
        put("text_int_max", q.textIntMax)
        put("text_random_mode", desktopTextRandomMode(q))
        put("text_random_int_range", JSONArray().put(q.textIntMin).put(q.textIntMax))
        put("option_fill_texts", JSONArray(q.optionFillTexts.map { it ?: JSONObject.NULL }))
        put("fillable_option_indices", JSONArray(q.fillableOptionIndices))
        put("attached_option_selects", JSONArray(q.attachedOptionSelects.map { JSONObject(it) }))
        put("multi_text_blank_modes", JSONArray(desktopMultiTextBlankModes(q)))
        put("multi_text_blank_ai_flags", JSONArray(q.multiTextBlankAiFlags))
        put("multi_text_blank_int_ranges", JSONArray(q.multiTextBlankIntRanges.map { JSONArray(it) }))
        put("is_location", q.entryType == QuestionEntryType.LOCATION)
        put("location_parts", JSONArray(q.locationParts))
        put("slider_target", q.sliderTarget)
        q.sliderMin?.let { put("slider_min", it) }
        q.sliderMax?.let { put("slider_max", it) }
        put("dimension", q.dimension ?: JSONObject.NULL)
        put("distribution_mode", desktopDistributionMode(q))
        if (q.distributionMode.trim().equals("weighted", ignoreCase = true)) {
            put("android_distribution_mode", "weighted")
        }
        val psychoBias: Any = if (q.entryType == QuestionEntryType.MATRIX) {
            JSONArray(resolveMatrixBiasPresets(q))
        } else {
            normalizePsychoBias(q.biasPreset)
        }
        put("psycho_bias", psychoBias)
        put("bias_preset", psychoBias)
    }

    fun deserialize(json: String): SurveyConfigDraft {
        val root = JSONObject(json)
        ensureSupportedConfigPayload(root)
        val provider = SurveyProviderType.fromId(root.optString("survey_provider", "wjx"))
        val metas = mutableListOf<SurveyQuestionMeta>()
        root.optJSONArray("questions_info")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { metas.add(deserializeMeta(it, provider.id)) }
        }
        val def = SurveyDefinition(provider, root.optString("url"), root.optString("survey_title"), metas)
        val answerDuration = normalizeAnswerDurationRange(root.opt("answer_duration"))

        val params = RunParamsDraft(
            targetNum = root.optInt("target", 1),
            numThreads = root.optInt("threads", 1),
            answerDurationMin = answerDuration.first,
            answerDurationMax = answerDuration.second,
            submitIntervalMin = root.optJSONArray("submit_interval")?.optInt(0, 0) ?: 0,
            submitIntervalMax = root.optJSONArray("submit_interval")?.optInt(1, 0) ?: 0,
            answerDatetimeStart = root.optJSONArray("answer_datetime_window")?.optString(0, "") ?: "",
            answerDatetimeEnd = root.optJSONArray("answer_datetime_window")?.optString(1, "") ?: "",
            randomProxyIpEnabled = root.optBoolean("random_ip_enabled", false),
            proxySource = normalizeProxySource(root.optString("proxy_source", "default")),
            customProxyApi = root.optString("custom_proxy_api", ""),
            proxyAreaCode = root.optString("proxy_area_code", "").ifBlank { null },
            randomUserAgentEnabled = root.optBoolean("random_ua_enabled", false),
            randomUserAgentRatios = normalizeUserAgentRatios(root.optJSONObject("random_ua_ratios").toStringIntMap()),
            stopOnFailEnabled = root.optBoolean("fail_stop_enabled", true),
            failThreshold = root.optInt("fail_threshold", 5),
            reliabilityModeEnabled = root.optBoolean("reliability_mode_enabled", true),
            psychoTargetAlpha = root.optDouble("psycho_target_alpha", 0.85),
            pauseOnAliyunCaptcha = root.optBoolean("pause_on_aliyun_captcha", true),
            aiMode = root.optString("ai_mode", "free"),
        )
        val preserved = ConfigPreservedFields(
            dimensionGroups = normalizeDimensionGroups(root.optJSONArray("dimension_groups").toStringList()),
            reverseFillEnabled = root.optBoolean("reverse_fill_enabled", false),
            reverseFillSourcePath = root.optString("reverse_fill_source_path", ""),
            reverseFillFormat = normalizeReverseFillFormat(root.optString("reverse_fill_format", "auto")),
            reverseFillStartRow = root.optInt("reverse_fill_start_row", 1).coerceAtLeast(1),
            reverseFillThreads = root.optInt("reverse_fill_threads", params.numThreads).coerceAtLeast(1),
            importedAiConfig = root.importedAiConfig(),
        )

        val rawAnswerRules = mutableListOf<Map<String, Any?>>()
        root.optJSONArray("answer_rules")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                rawAnswerRules.add(o.keys().asSequence().associateWith { o.opt(it).let { v -> jsonToKotlin(v) } })
            }
        }
        val answerRules = AnswerRule.sanitizeRules(rawAnswerRules, metas)

        val drafts = mutableListOf<QuestionConfigDraft>()
        root.optJSONArray("question_entries")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val meta = resolveEntryMeta(e, metas, provider.id)
                if (meta != null && (meta.isDescription || meta.unsupported)) continue
                drafts.add(deserializeEntry(e, meta, provider.id))
            }
        }
        if (drafts.isEmpty() && metas.isNotEmpty()) {
            return SurveyConfigDraft.fromDefinition(def).copy(params = params, answerRules = answerRules, preserved = preserved)
        }
        return SurveyConfigDraft(def, drafts, params, answerRules, preserved)
    }

    private fun resolveEntryMeta(e: JSONObject, metas: List<SurveyQuestionMeta>, defaultProvider: String): SurveyQuestionMeta? {
        val num = e.optInt("question_num")
        metas.firstOrNull { it.num == num }?.let { return it }
        val pageId = e.optString("provider_page_id", "").trim()
        val questionId = e.optString("provider_question_id", "").trim()
        val provider = normalizeEntryProvider(e.optString("survey_provider", ""), questionId, defaultProvider)
        val providerKey = ExecutionConfig.providerQuestionKey(
            provider,
            pageId,
            questionId,
        )
        if (providerKey.isNotEmpty()) {
            return metas.firstOrNull { meta ->
                ExecutionConfig.providerQuestionKey(
                    meta.provider.ifBlank { defaultProvider },
                    meta.providerPageId,
                    meta.providerQuestionId,
                ) == providerKey
            }
        }
        if (questionId.isEmpty()) return null
        val matches = metas.filter { meta ->
            val metaProvider = meta.provider.ifBlank { defaultProvider }.trim().lowercase()
            metaProvider == provider && meta.providerQuestionId == questionId
        }
        return matches.singleOrNull()
    }

    private fun deserializeMeta(o: JSONObject, defaultProvider: String): SurveyQuestionMeta {
        val jumpRules = o.optJSONArray("jump_rules").toJumpRules()
        val displayConditions = o.optJSONArray("display_conditions").toDisplayConditions()
        val displayTargets = o.optJSONArray("controls_display_targets").toDisplayTargets()
        val hasJump = o.optBoolean("has_jump")
        val hasDisplayCondition = o.optBoolean("has_display_condition")
        val hasDependentDisplayLogic = o.optBoolean("has_dependent_display_logic")
        val num = o.optInt("num").coerceAtLeast(1)
        val page = o.optInt("page", 1).coerceAtLeast(1)
        val typeCode = o.optString("type_code", "0").trim().ifBlank { "0" }
        val providerType = o.optString("provider_type", "").trim().ifBlank { typeCode }
        val isDescription = o.optBoolean("is_description") || providerType.equals("description", ignoreCase = true)
        val unsupported = o.optBoolean("unsupported", false) && !isDescription
        val unsupportedReason = o.optString("unsupported_reason", "").trim()
        return SurveyQuestionMeta(
            num = num,
            displayNum = if (o.has("display_num") && !o.isNull("display_num")) o.optInt("display_num") else null,
            title = o.optString("title").trim(),
            typeCode = typeCode,
            page = page,
            options = o.optInt("options"),
            rows = o.optInt("rows", 1).coerceAtLeast(1),
            optionTexts = o.optJSONArray("option_texts").toStringList(),
            rowTexts = o.optJSONArray("row_texts").toStringList(),
            forcedOptionIndex = if (o.has("forced_option_index") && !o.isNull("forced_option_index")) o.optInt("forced_option_index") else null,
            forcedOptionText = o.optString("forced_option_text", "").ifBlank { null },
            forcedTexts = o.optJSONArray("forced_texts").toStringList(),
            textInputs = o.optInt("text_inputs").coerceAtLeast(0),
            textInputLabels = o.optJSONArray("text_input_labels").toStringList(),
            fillableOptions = o.optJSONArray("fillable_options").toIntList(),
            attachedOptionSelects = o.optJSONArray("attached_option_selects").toMapList(),
            hasAttachedOptionSelect = o.optBoolean("has_attached_option_select", o.optJSONArray("attached_option_selects")?.length()?.let { it > 0 } ?: false),
            multiMinLimit = if (o.has("multi_min_limit") && !o.isNull("multi_min_limit")) o.optInt("multi_min_limit") else null,
            multiMaxLimit = if (o.has("multi_max_limit") && !o.isNull("multi_max_limit")) o.optInt("multi_max_limit") else null,
            sliderMin = o.optNullableDouble("slider_min"),
            sliderMax = o.optNullableDouble("slider_max"),
            sliderStep = o.optNullableDouble("slider_step"),
            required = o.optBoolean("required", false),
            provider = normalizeProviderId(o.optString("provider", "")).ifBlank { defaultProvider },
            providerQuestionId = o.optString("provider_question_id").trim().ifBlank { num.toString() },
            providerPageId = o.optString("provider_page_id").trim().ifBlank { page.toString() },
            providerType = providerType,
            providerPageRaw = o.optJSONObject("provider_page_raw")?.let {
                @Suppress("UNCHECKED_CAST")
                jsonToKotlin(it) as? Map<String, Any?>
            },
            isLocation = o.optBoolean("is_location"),
            isRating = o.optBoolean("is_rating"),
            ratingMax = o.optInt("rating_max").coerceAtLeast(0),
            isDescription = isDescription,
            isTextLike = o.optBoolean("is_text_like"),
            isMultiText = o.optBoolean("is_multi_text"),
            isSliderMatrix = o.optBoolean("is_slider_matrix"),
            hasJump = hasJump,
            jumpRules = jumpRules,
            hasDisplayCondition = hasDisplayCondition,
            displayConditions = displayConditions,
            hasDependentDisplayLogic = hasDependentDisplayLogic,
            controlsDisplayTargets = displayTargets,
            logicParseStatus = inferLogicParseStatus(o, hasJump, jumpRules, hasDisplayCondition, displayConditions, hasDependentDisplayLogic, displayTargets),
            questionMedia = o.optJSONArray("question_media").toQuestionMediaList(),
            unsupported = unsupported,
            unsupportedReason = if (unsupported) unsupportedReason.ifBlank { "当前平台暂不支持该题型" } else unsupportedReason,
            description = o.optString("description", "").trim(),
        )
    }

    private fun inferLogicParseStatus(
        o: JSONObject,
        hasJump: Boolean,
        jumpRules: List<JumpRule>,
        hasDisplayCondition: Boolean,
        displayConditions: List<DisplayCondition>,
        hasDependentDisplayLogic: Boolean,
        displayTargets: List<DisplayTarget>,
    ): String {
        if (o.has("logic_parse_status")) {
            val explicit = o.optString("logic_parse_status", "").trim().lowercase()
            return if (explicit in VALID_LOGIC_PARSE_STATUSES) explicit else "unknown"
        }
        val hasLogic = hasJump || hasDisplayCondition || hasDependentDisplayLogic
        if (!hasLogic) return "none"
        return if (jumpRules.isNotEmpty() || displayConditions.isNotEmpty() || displayTargets.isNotEmpty()) {
            "complete"
        } else {
            "unknown"
        }
    }

    private fun normalizeLogicParseStatus(raw: String): String {
        val value = raw.trim().lowercase()
        return if (value in VALID_LOGIC_PARSE_STATUSES) value else "unknown"
    }

    private fun normalizeProxySource(raw: String?): String {
        val normalized = raw?.trim()?.lowercase().orEmpty().ifBlank { "default" }
        return if (normalized in VALID_PROXY_SOURCES) normalized else "default"
    }

    private fun deserializeEntry(e: JSONObject, meta: SurveyQuestionMeta?, defaultProvider: String): QuestionConfigDraft {
        val optionTexts = meta?.optionTexts ?: emptyList()
        val rowTexts = meta?.rowTexts ?: emptyList()
        val entryProvider = normalizeEntryProvider(
            e.optString("survey_provider", ""),
            e.optString("provider_question_id", ""),
            defaultProvider,
        )
        val entryType = when {
            meta != null -> meta.entryType
            e.optBoolean("is_location", false) -> QuestionEntryType.LOCATION
            else -> idToEntryType(e.optString("question_type"))
        }
        val optionCount = maxOf(1, optionTexts.size.takeIf { it > 0 } ?: e.optInt("option_count", 1))
        val fillableIndices = e.optJSONArray("fillable_option_indices").toIntList()
            .ifEmpty { meta?.fillableOptions ?: emptyList() }
            .filter { it in 0 until optionCount }
        val optionFillTexts = e.optJSONArray("option_fill_texts").toNullableStringList()
            .padNullable(optionCount)
        val attachedSelects = e.optJSONArray("attached_option_selects").toMapList()
            .ifEmpty { meta?.attachedOptionSelects ?: emptyList() }
        val blankCount = maxOf(1, meta?.textInputs ?: 1)
        val rowCount = maxOf(1, rowTexts.size, meta?.rows ?: e.optInt("rows", 1))
        val textIntRange = e.resolveTextIntRange()
        val biasValue = e.resolvePsychoBias()
        val forcedTextCandidates = forcedTextCandidates(meta, entryType)
        val textCandidates = forcedTextCandidates
            ?: e.optJSONArray("texts").toStringList().toMutableList().ifEmpty { mutableListOf("已填写") }
        val weightCount = if (entryType == QuestionEntryType.TEXT || entryType == QuestionEntryType.MULTI_TEXT) textCandidates.size else optionCount
        val weightPadValue = if (e.entryWeightsArray() == null || entryType == QuestionEntryType.TEXT || entryType == QuestionEntryType.MULTI_TEXT) 1.0 else 0.0
        val forcedOptionWeights = forcedOptionWeights(meta, entryType, optionCount)
        return QuestionConfigDraft(
            num = meta?.num ?: e.optInt("question_num"),
            title = e.optString("question_title").ifBlank { meta?.title ?: "" },
            entryType = entryType,
            optionTexts = optionTexts,
            rowTexts = rowTexts,
            surveyProvider = entryProvider.ifBlank { meta?.provider ?: defaultProvider },
            providerQuestionId = e.optString("provider_question_id", "")
                .ifBlank { meta?.providerQuestionId ?: "" },
            providerPageId = e.optString("provider_page_id", "")
                .ifBlank { meta?.providerPageId ?: "" },
            optionWeights = forcedOptionWeights
                ?: e.entryWeightsListOrDefault(entryType, weightCount, weightPadValue),
            multiRandomCount = e.optBoolean("multi_random_count"),
            multiProbabilities = e.resolveMultiProbabilities(optionCount),
            matrixRowWeights = e.resolveMatrixRowWeights(rowCount, optionCount),
            textCandidates = textCandidates,
            useAiText = forcedTextCandidates == null && e.optBoolean("ai_enabled"),
            textMode = normalizeTextMode(
                if (forcedTextCandidates != null) "custom"
                else e.optString("text_mode", "").ifBlank { e.optString("text_random_mode", "custom") },
            ),
            textIntMin = textIntRange.first,
            textIntMax = textIntRange.second,
            fillableOptionIndices = fillableIndices,
            optionFillTexts = optionFillTexts,
            attachedOptionSelects = attachedSelects,
            textInputLabels = meta?.textInputLabels ?: emptyList(),
            multiTextBlankModes = if (forcedTextCandidates != null) {
                MutableList(blankCount) { "custom" }
            } else {
                e.optJSONArray("multi_text_blank_modes").toStringList()
                    .map { normalizeTextMode(it) }
                    .padStrings(blankCount, "custom")
                    .toMutableList()
            },
            multiTextBlankAiFlags = if (forcedTextCandidates != null) {
                MutableList(blankCount) { false }
            } else {
                e.optJSONArray("multi_text_blank_ai_flags").toBooleanList()
                    .padBooleans(blankCount)
                    .toMutableList()
            },
            multiTextBlankIntRanges = e.optJSONArray("multi_text_blank_int_ranges").toIntRangeList()
                .padRanges(blankCount)
                .toMutableList(),
            locationParts = e.optJSONArray("location_parts").toStringList()
                .padStrings(3, "")
                .take(3)
                .toMutableList(),
            sliderTarget = e.resolveSliderTarget(),
            sliderMin = e.optNullableDouble("slider_min") ?: meta?.sliderMin,
            sliderMax = e.optNullableDouble("slider_max") ?: meta?.sliderMax,
            dimension = e.optString("dimension", "").ifBlank { null },
            distributionMode = if (forcedOptionWeights != null) "custom" else e.resolveDistributionMode(entryType),
            biasPreset = biasValue.asSingleBias(),
            matrixBiasPresets = biasValue.asBiasList(rowCount).toMutableList(),
        )
    }

    private fun normalizeEntryProvider(rawProvider: String?, providerQuestionId: String?, defaultProvider: String): String {
        val normalized = normalizeProviderId(rawProvider.orEmpty()).ifBlank { defaultProvider }
        val hasProviderQuestionId = !providerQuestionId.isNullOrBlank()
        return if (defaultProvider != SurveyProviderType.WJX.id && hasProviderQuestionId && normalized == SurveyProviderType.WJX.id) {
            defaultProvider
        } else {
            normalized
        }
    }

    private fun forcedTextCandidates(meta: SurveyQuestionMeta?, entryType: QuestionEntryType): MutableList<String>? {
        if (entryType != QuestionEntryType.TEXT && entryType != QuestionEntryType.MULTI_TEXT) return null
        val forced = meta?.forcedTexts
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        return forced.takeIf { it.isNotEmpty() }?.toMutableList()
    }

    private fun forcedOptionWeights(meta: SurveyQuestionMeta?, entryType: QuestionEntryType, optionCount: Int): MutableList<Double>? {
        if (entryType !in setOf(QuestionEntryType.SINGLE, QuestionEntryType.DROPDOWN, QuestionEntryType.SCALE, QuestionEntryType.SCORE)) {
            return null
        }
        val count = maxOf(1, optionCount)
        val index = meta?.forcedOptionIndex?.takeIf { it in 0 until count } ?: return null
        return MutableList(count) { idx -> if (idx == index) 100.0 else 0.0 }
    }

    private fun entryTypeId(t: QuestionEntryType): String = when (t) {
        QuestionEntryType.SINGLE -> "single"; QuestionEntryType.MULTIPLE -> "multiple"
        QuestionEntryType.DROPDOWN -> "dropdown"; QuestionEntryType.SCALE -> "scale"
        QuestionEntryType.SCORE -> "score"; QuestionEntryType.MATRIX -> "matrix"
        QuestionEntryType.SLIDER -> "slider"; QuestionEntryType.ORDER -> "order"
        QuestionEntryType.TEXT -> "text"; QuestionEntryType.MULTI_TEXT -> "multi_text"
        QuestionEntryType.LOCATION -> "location"
    }

    private fun idToEntryType(id: String): QuestionEntryType = when (id) {
        "multilevel_dropdown" -> QuestionEntryType.DROPDOWN
        "multiple" -> QuestionEntryType.MULTIPLE; "dropdown" -> QuestionEntryType.DROPDOWN
        "scale" -> QuestionEntryType.SCALE; "score" -> QuestionEntryType.SCORE
        "matrix" -> QuestionEntryType.MATRIX; "slider" -> QuestionEntryType.SLIDER
        "order" -> QuestionEntryType.ORDER; "text" -> QuestionEntryType.TEXT
        "multi_text" -> QuestionEntryType.MULTI_TEXT; "location" -> QuestionEntryType.LOCATION
        else -> QuestionEntryType.SINGLE
    }

    private val psychoBiasValues = setOf("custom", "left", "center", "right")

    private data class PsychoBiasValue(val single: String, val rows: List<String> = emptyList()) {
        fun asSingleBias(): String = single
        fun asBiasList(rowCount: Int): List<String> {
            val count = maxOf(1, rowCount)
            val source = rows.ifEmpty { listOf(single) }
            return List(count) { idx -> source.getOrElse(idx) { source.lastOrNull() ?: "custom" } }
        }
    }

    private fun normalizePsychoBias(raw: Any?): String {
        val value = raw?.toString()?.trim()?.lowercase().orEmpty()
        return if (value in psychoBiasValues) value else "custom"
    }

    private fun resolveMatrixBiasPresets(q: QuestionConfigDraft): List<String> {
        val rowCount = maxOf(1, q.rowTexts.size, q.matrixRowWeights.size)
        val source = q.matrixBiasPresets.ifEmpty { mutableListOf(q.biasPreset) }
        return List(rowCount) { idx -> normalizePsychoBias(source.getOrElse(idx) { source.lastOrNull() ?: q.biasPreset }) }
    }

    private fun JSONObject.resolvePsychoBias(): PsychoBiasValue {
        val raw = when {
            has("psycho_bias") && !isNull("psycho_bias") -> opt("psycho_bias")
            has("bias_preset") && !isNull("bias_preset") -> opt("bias_preset")
            else -> null
        }
        val rows = when (raw) {
            is JSONArray -> raw.toStringList().map { normalizePsychoBias(it) }
            is List<*> -> raw.map { normalizePsychoBias(it) }
            else -> emptyList()
        }.filter { it.isNotEmpty() }
        if (rows.isNotEmpty()) return PsychoBiasValue(rows.first(), rows)
        return PsychoBiasValue(normalizePsychoBias(raw))
    }

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }

    private fun JSONArray?.toNullableStringList(): MutableList<String?> =
        if (this == null) {
            mutableListOf()
        } else {
            (0 until length()).map { idx ->
                val raw = opt(idx)
                if (raw == null || raw == JSONObject.NULL) null else raw.toString()
            }.toMutableList()
        }

    private fun JSONArray?.toIntList(): List<Int> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { idx ->
            val value = opt(idx)
            when (value) {
                is Number -> value.toInt()
                else -> value?.toString()?.toIntOrNull()
            }
        }

    private fun JSONArray?.toMapList(): List<Map<String, Any?>> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { idx ->
            val item = optJSONObject(idx) ?: return@mapNotNull null
            item.keys().asSequence().associateWith { key -> jsonToKotlin(item.opt(key)) }
        }
    }

    private fun JSONArray?.toJumpRules(): List<JumpRule> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { idx ->
            val item = optJSONObject(idx) ?: return@mapNotNull null
            val target = if (item.has("jumpto") && !item.isNull("jumpto")) item.optInt("jumpto") else null
            val optionText = item.optString("option_text", "").ifBlank { null }
            JumpRule(
                optionIndex = item.optInt("option_index", -1),
                targetQuestion = target,
                optionText = optionText,
                terminates = if (item.has("terminates_survey")) {
                    item.optBoolean("terminates_survey", false)
                } else {
                    optionText?.let { text -> TERMINATE_JUMP_KEYWORDS.any { it in text } } == true
                },
            )
        }
    }

    private fun JSONArray?.toDisplayConditions(): List<DisplayCondition> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { idx ->
            val item = optJSONObject(idx) ?: return@mapNotNull null
            DisplayCondition(
                conditionQuestionNum = item.optInt("condition_question_num"),
                conditionMode = item.optString("condition_mode", "selected").ifBlank { "selected" },
                conditionOptionIndices = item.optJSONArray("condition_option_indices").toIntList(),
            )
        }
    }

    private fun JSONArray?.toDisplayTargets(): List<DisplayTarget> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { idx ->
            val item = optJSONObject(idx) ?: return@mapNotNull null
            DisplayTarget(
                targetQuestionNum = item.optInt("target_question_num"),
                conditionMode = item.optString("condition_mode", "selected").ifBlank { "selected" },
                conditionOptionIndices = item.optJSONArray("condition_option_indices").toIntList(),
            )
        }
    }

    private fun JSONArray?.toQuestionMediaList(): List<QuestionMedia> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { idx ->
            val item = optJSONObject(idx) ?: return@mapNotNull null
            val kind = item.optString("kind", "").trim().lowercase()
            if (kind != "image") return@mapNotNull null
            val scope = item.optString("scope", "").trim().lowercase()
            if (scope !in setOf("title", "option", "row")) return@mapNotNull null
            val sourceUrl = item.optString("source_url", "").trim()
            if (sourceUrl.isEmpty()) return@mapNotNull null
            val index = if (scope == "title" || !item.has("index") || item.isNull("index")) {
                null
            } else {
                item.optInt("index").takeIf { it >= 0 } ?: return@mapNotNull null
            }
            QuestionMedia(
                kind = "image",
                scope = scope,
                index = index,
                sourceUrl = sourceUrl,
                label = item.optString("label", "").trim(),
            )
        }
    }

    private fun JSONArray?.toBooleanList(): List<Boolean> =
        if (this == null) emptyList() else (0 until length()).map { idx ->
            val value = opt(idx)
            when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                else -> value?.toString()?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
            }
        }

    private fun JSONArray?.toIntRangeList(): List<MutableList<Int>> {
        if (this == null) return emptyList()
        return (0 until length()).map { idx ->
            val arr = optJSONArray(idx)
            mutableListOf(arr?.optInt(0, 0) ?: 0, arr?.optInt(1, 100) ?: 100)
        }
    }

    private fun JSONArray?.toDoubleList(): MutableList<Double> =
        if (this == null) mutableListOf() else (0 until length()).map { optDouble(it, 0.0) }.toMutableList()

    private fun JSONObject.entryWeightsListOrDefault(entryType: QuestionEntryType, count: Int, value: Double): MutableList<Double> {
        val explicit = entryWeightsArray()
        if (explicit != null) {
            val weights = explicit.toDoubleList()
            val preserveShape = optString("distribution_mode", "").trim().lowercase() == "custom" ||
                entryType == QuestionEntryType.TEXT ||
                entryType == QuestionEntryType.MULTI_TEXT
            return if (preserveShape) weights else weights.fitToSize(count, value)
        }
        return MutableList(maxOf(1, count)) { value }
    }

    private fun JSONObject.resolveMultiProbabilities(optionCount: Int): MutableList<Double> {
        val explicit = optJSONArray("multi_probabilities")
        if (explicit != null) {
            return if (optBoolean("multi_random_count")) {
                explicit.toDoubleList().fitToSize(optionCount, 50.0)
            } else {
                explicit.toDoubleList()
            }
        }
        val weights = entryWeightsArray()
        if (weights != null) {
            val probabilities = weights.toDoubleList()
            val preserveShape = optString("distribution_mode", "").trim().lowercase() == "custom"
            return if (preserveShape && !optBoolean("multi_random_count")) {
                probabilities
            } else {
                probabilities.fitToSize(optionCount, 50.0)
            }
        }
        return MutableList(maxOf(1, optionCount)) { 50.0 }
    }

    private fun MutableList<Double>.fitToSize(count: Int, value: Double): MutableList<Double> {
        val normalizedCount = maxOf(1, count)
        if (isEmpty()) return MutableList(normalizedCount) { value }
        while (size < normalizedCount) add(value)
        while (size > normalizedCount) removeAt(size - 1)
        return this
    }

    private fun List<Double>.fitRow(optionCount: Int, value: Double = 1.0): MutableList<Double> =
        toMutableList().fitToSize(optionCount, value)

    private fun JSONObject.entryWeightsArray(): JSONArray? {
        val probabilities = weightsArray("probabilities")
        val customWeights = weightsArray("custom_weights")
        val customMode = optString("distribution_mode", "").trim().lowercase() == "custom"
        if (customMode && isUnsetWeights(opt("probabilities")) && customWeights.hasPositiveWeight()) {
            return customWeights
        }
        return probabilities ?: customWeights
    }

    private fun JSONObject.weightsArray(key: String): JSONArray? {
        val raw = opt(key)
        if (raw == null || raw == JSONObject.NULL) return null
        if (raw is JSONArray) return raw
        val value = numericValue(raw) ?: return null
        if (value == -1.0) return null
        return JSONArray().put(value)
    }

    private fun isUnsetWeights(raw: Any?): Boolean {
        if (raw == null || raw == JSONObject.NULL) return true
        if (raw is JSONArray) {
            if (raw.length() == 0) return true
            for (idx in 0 until raw.length()) {
                val item = raw.opt(idx)
                if (item is JSONArray) {
                    if (item.hasPositiveWeight()) return false
                    continue
                }
                val value = numericValue(item) ?: continue
                if (value > 0.0) return false
            }
            return true
        }
        val value = numericValue(raw)
        return value == null || value == -1.0 || value <= 0.0
    }

    private fun JSONArray?.hasPositiveWeight(): Boolean {
        if (this == null) return false
        for (idx in 0 until length()) {
            val item = opt(idx)
            if (item is JSONArray) {
                if (item.hasPositiveWeight()) return true
                continue
            }
            val value = numericValue(item) ?: 0.0
            if (value > 0.0) return true
        }
        return false
    }

    private fun JSONObject.resolveDistributionMode(entryType: QuestionEntryType): String {
        val androidMode = optString("android_distribution_mode", "").trim().lowercase()
        if (androidMode == "weighted") return "weighted"
        val explicit = optString("distribution_mode", "").trim().lowercase()
        if (explicit == "custom") return "custom"
        if (explicit == "weighted") return "weighted"
        if (optBoolean("strict_ratio")) return "custom"
        if (entryType in setOf(
                QuestionEntryType.SINGLE,
                QuestionEntryType.DROPDOWN,
                QuestionEntryType.SCALE,
                QuestionEntryType.SCORE,
                QuestionEntryType.MATRIX,
            ) && weightsArray("probabilities").hasPositiveWeight()
        ) {
            return "weighted"
        }
        return "random"
    }

    private fun JSONObject.resolveMatrixRowWeights(rowCount: Int, optionCount: Int): MutableList<MutableList<Double>> {
        val preserveShape = optString("distribution_mode", "").trim().lowercase() == "custom"
        optJSONArray("matrix_row_weights")?.let { explicit ->
            return if (preserveShape) explicit.toMatrixRowsPreservingShape() else explicit.toMatrixRows(rowCount, optionCount)
        }
        val source = entryWeightsArray()
        if (source != null) {
            return if (preserveShape) source.toMatrixRowsPreservingShape() else source.toMatrixRows(rowCount, optionCount)
        }
        return MutableList(maxOf(1, rowCount)) { MutableList(maxOf(1, optionCount)) { 1.0 } }
    }

    private fun JSONArray.toMatrixRowsPreservingShape(): MutableList<MutableList<Double>> {
        val nestedRows = (0 until length()).mapNotNull { idx ->
            optJSONArray(idx)?.toDoubleList()
        }
        if (nestedRows.isNotEmpty()) return nestedRows.toMutableList()
        val flat = toDoubleList()
        return if (flat.isEmpty() || (flat.size == 1 && flat[0] == -1.0)) {
            mutableListOf(mutableListOf(1.0))
        } else {
            mutableListOf(flat)
        }
    }

    private fun JSONArray.toMatrixRows(rowCount: Int, optionCount: Int): MutableList<MutableList<Double>> {
        val normalizedRows = maxOf(1, rowCount)
        val nestedRows = (0 until length()).mapNotNull { idx ->
            optJSONArray(idx)?.toDoubleList()?.fitRow(optionCount)
        }
        if (nestedRows.isNotEmpty()) {
            val rows = nestedRows.toMutableList()
            while (rows.size < normalizedRows) rows.add(rows.last().toList().fitRow(optionCount))
            while (rows.size > normalizedRows) rows.removeAt(rows.lastIndex)
            return rows
        }
        val flat = toDoubleList()
        val row = if (flat.isEmpty() || (flat.size == 1 && flat[0] == -1.0)) {
            MutableList(maxOf(1, optionCount)) { 1.0 }
        } else {
            flat.fitToSize(optionCount, 1.0)
        }
        return MutableList(normalizedRows) { row.toList().fitRow(optionCount) }
    }

    private fun JSONObject.resolveTextIntRange(): Pair<Int, Int> {
        val explicitMin = if (has("text_int_min") && !isNull("text_int_min")) optInt("text_int_min", 0) else null
        val explicitMax = if (has("text_int_max") && !isNull("text_int_max")) optInt("text_int_max", 100) else null
        if (explicitMin != null || explicitMax != null) {
            val low = explicitMin ?: 0
            val high = explicitMax ?: 100
            return low to high
        }
        val range = optJSONArray("text_random_int_range").toIntList()
        if (range.size >= 2) {
            val low = range[0]
            val high = range[1]
            return low to high
        }
        return 0 to 100
    }

    private fun JSONObject.resolveSliderTarget(): Double {
        optNullableDouble("slider_target")?.let { return it }
        val weights = entryWeightsArray()
        val first = weights?.let { if (it.length() > 0) it.opt(0) else null }
        val value = numericValue(first)
        if (value != null && value.isFinite()) {
            val randomMode = optString("distribution_mode", "").trim().lowercase() == "random"
            if (!(randomMode && value == -1.0)) return value
        }
        return 50.0
    }

    private fun MutableList<String?>.padNullable(count: Int): MutableList<String?> {
        while (size < count) add(null)
        while (size > count) removeAt(size - 1)
        return this
    }

    private fun List<String>.padStrings(count: Int, value: String): List<String> {
        val out = toMutableList()
        while (out.size < count) out.add(value)
        return out.take(count)
    }

    private fun List<Boolean>.padBooleans(count: Int): List<Boolean> {
        val out = toMutableList()
        while (out.size < count) out.add(false)
        return out.take(count)
    }

    private fun List<MutableList<Int>>.padRanges(count: Int): List<MutableList<Int>> {
        val out = toMutableList()
        while (out.size < count) out.add(mutableListOf(0, 100))
        return out.take(count)
    }

    private fun JSONObject?.toStringIntMap(): Map<String, Int> {
        if (this == null) return emptyMap()
        val out = mutableMapOf<String, Int>()
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val value = opt(key)
            out[key] = when (value) {
                is Number -> value.toInt()
                else -> value?.toString()?.toIntOrNull() ?: 0
            }
        }
        return out
    }

    private fun normalizeUserAgentRatios(raw: Map<String, Int>): Map<String, Int> {
        val keys = listOf("wechat", "mobile", "pc")
        val values = keys.map { key -> (raw[key] ?: 0).coerceIn(0, 100) }
        return if (values.sum() == 100) keys.zip(values).toMap() else mapOf("wechat" to 33, "mobile" to 33, "pc" to 34)
    }

    private fun normalizeReverseFillFormat(raw: String): String {
        val value = raw.trim().lowercase().ifBlank { "auto" }
        return if (value in setOf("auto", "wjx_sequence", "wjx_score", "wjx_text")) value else "auto"
    }

    private fun legacyAnswerDurationToRange(value: Int): Pair<Int, Int> {
        val normalized = value.coerceIn(0, MAX_ANSWER_DURATION_SECONDS)
        if (normalized <= 0) return DEFAULT_ANSWER_DURATION_MIN to DEFAULT_ANSWER_DURATION_MAX
        val low = Math.round(normalized * 0.9).toInt().coerceAtLeast(0)
        val high = Math.round(normalized * 1.1).toInt()
            .coerceAtLeast(low)
            .coerceAtMost(MAX_ANSWER_DURATION_SECONDS)
        return low to high
    }

    private fun normalizeAnswerDurationRange(raw: Any?): Pair<Int, Int> {
        if (raw == null || raw == JSONObject.NULL) return DEFAULT_ANSWER_DURATION_MIN to DEFAULT_ANSWER_DURATION_MAX
        if (raw is JSONArray) {
            if (raw.length() >= 2) {
                val low = raw.optInt(0, DEFAULT_ANSWER_DURATION_MIN)
                val high = raw.optInt(1, DEFAULT_ANSWER_DURATION_MAX)
                if (low == 0 && high == 0) return DEFAULT_ANSWER_DURATION_MIN to DEFAULT_ANSWER_DURATION_MAX
                if (low == high) return legacyAnswerDurationToRange(low)
                return low to high
            }
            if (raw.length() == 1) return legacyAnswerDurationToRange(raw.optInt(0, 0))
            return DEFAULT_ANSWER_DURATION_MIN to DEFAULT_ANSWER_DURATION_MAX
        }
        val value = when (raw) {
            is Number -> raw.toInt()
            else -> raw.toString().trim().toIntOrNull()
        }
        return value?.let { legacyAnswerDurationToRange(it) }
            ?: (DEFAULT_ANSWER_DURATION_MIN to DEFAULT_ANSWER_DURATION_MAX)
    }

    private fun normalizeDimensionGroups(raw: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (item in raw) {
            val text = item.trim()
            if (text.isEmpty() || text == "未分组") continue
            seen.add(text)
        }
        return seen.toList()
    }

    private fun JSONObject.importedAiConfig(): ImportedAiConfig {
        val keys = listOf("ai_mode", "ai_provider", "ai_api_key", "ai_base_url", "ai_api_protocol", "ai_model", "ai_system_prompt")
        val present = keys.any { has(it) }
        val mode = normalizeAiMode(optString("ai_mode", "free"))
        return ImportedAiConfig(
            present = present,
            mode = mode,
            provider = optString("ai_provider", "deepseek").ifBlank { "deepseek" },
            apiKey = optString("ai_api_key", ""),
            baseUrl = optString("ai_base_url", ""),
            apiProtocol = optString("ai_api_protocol", "auto").ifBlank { "auto" },
            model = optString("ai_model", ""),
            systemPrompt = optString("ai_system_prompt", ""),
        )
    }

    private fun normalizeAiMode(raw: String): String {
        val value = raw.trim().lowercase()
        return if (value in setOf("free", "provider")) value else "free"
    }

    private fun ensureSupportedConfigPayload(root: JSONObject) {
        val legacyKeys = LEGACY_CONFIG_KEYS.filter { root.has(it) }
        if (legacyKeys.isNotEmpty()) {
            throw IllegalArgumentException("配置文件使用了已移除的旧字段（${legacyKeys.joinToString("、")}），请先用桌面端新版重新保存后再导入")
        }
        val rawVersion = root.opt("config_schema_version")
        val version = when (rawVersion) {
            is Number -> rawVersion.toInt()
            else -> rawVersion?.toString()?.trim()?.toIntOrNull() ?: 0
        }
        if (version != SCHEMA_VERSION) {
            throw IllegalArgumentException("配置文件版本不受支持（当前仅支持 schema v$SCHEMA_VERSION，实际为 v$version）")
        }
    }

    private fun normalizeProviderId(raw: String): String {
        val value = raw.trim().lowercase()
        return if (value in setOf("wjx", "qq", "credamo")) value else ""
    }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key)

    private fun numericValue(raw: Any?): Double? = when (raw) {
        null, JSONObject.NULL -> null
        is Number -> raw.toDouble()
        else -> raw.toString().trim().toDoubleOrNull()
    }?.takeIf { it.isFinite() }

    private fun normalizeTextMode(raw: String): String = when (raw.trim().lowercase()) {
        "none", "custom", "" -> "custom"
        "name", "random_name" -> "name"
        "mobile", "random_mobile" -> "mobile"
        "id_card", "random_id_card" -> "id_card"
        "integer", "random_integer" -> "integer"
        "generic" -> "generic"
        else -> "custom"
    }

    private fun jsonToKotlin(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONArray -> (0 until v.length()).map { jsonToKotlin(v.opt(it)) }
        is JSONObject -> v.keys().asSequence().associateWith { jsonToKotlin(v.opt(it)) }
        else -> v
    }
}
