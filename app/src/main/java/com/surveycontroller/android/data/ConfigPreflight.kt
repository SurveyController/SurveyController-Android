package com.surveycontroller.android.data

import com.surveycontroller.android.core.model.QuestionEntryType
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.questions.AnswerRule
import com.surveycontroller.android.core.questions.HttpLogicPlanner
import com.surveycontroller.android.core.questions.OPTION_FILL_AI_TOKEN
import com.surveycontroller.android.core.questions.TextValues
import com.surveycontroller.android.core.reverse_fill.REVERSE_FILL_STATUS_REVERSE
import com.surveycontroller.android.core.reverse_fill.ReverseFillSpec
import com.surveycontroller.android.provider.SurveyProviderType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 启动前配置体检。对齐桌面端 validate_question_config，并补上 Android 运行链路的显式风险提示。
 */
object ConfigPreflight {
    private const val MaxThreads = 16
    private const val MaxSubmitIntervalSeconds = 300
    private const val MaxAnswerDurationSeconds = 30 * 60

    enum class Severity { ERROR, WARNING }

    data class Issue(
        val severity: Severity,
        val title: String,
        val detail: String = "",
        val questionNum: Int? = null,
    )

    data class Options(
        val customAiEnabled: Boolean = false,
        val customAiBaseUrl: String = "",
        val customAiApiKey: String = "",
        val customAiModel: String = "",
        val customAiApiProtocol: String = "auto",
        val reverseFillEnabled: Boolean = false,
        val reverseFillSampleCount: Int = 0,
        val reverseFillSpec: ReverseFillSpec? = null,
    )

    data class Result(
        val errors: List<Issue> = emptyList(),
        val warnings: List<Issue> = emptyList(),
    ) {
        val canStart: Boolean get() = errors.isEmpty()

        fun blockingMessage(maxItems: Int = 8): String {
            if (errors.isEmpty()) return ""
            val lines = mutableListOf("启动预检未通过，请先处理：")
            errors.take(maxItems).forEach { issue ->
                lines.add("- ${issue.title}${issue.detail.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}")
            }
            if (errors.size > maxItems) lines.add("- 其余 ${errors.size - maxItems} 项已省略")
            return lines.joinToString("\n")
        }
    }

    private val textMinLengthPatterns = listOf(
        Regex("""(?:至少|最少|不少于|不低于)\s*(\d+)\s*(?:个)?(?:字|字符|汉字)"""),
        Regex("""(\d+)\s*(?:个)?(?:字|字符|汉字)\s*(?:以上|起)"""),
    )

    private val answerDatetimeFormats = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
    )

    fun validate(draft: SurveyConfigDraft, options: Options = Options()): Result {
        val errors = mutableListOf<Issue>()
        val warnings = mutableListOf<Issue>()
        val metaByNum = draft.definition.questions.associateBy { it.num }
        val questionIds = ConfigCodec.resolvedQuestionIds(draft)
        if (questionIds.size != questionIds.toSet().size) {
            errors.add(Issue(Severity.ERROR, "运行计划包含重复的题目 ID", "请重新解析问卷并检查配置中的重复题目"))
        }

        if (draft.questions.isEmpty()) {
            errors.add(Issue(Severity.ERROR, "未配置任何可作答题目", "请重新解析问卷，或导入包含 question_entries 的配置"))
        }
        if (draft.questions.isNotEmpty() && metaByNum.isEmpty()) {
            errors.add(Issue(Severity.ERROR, "缺少题目结构元数据", "请重新解析问卷，或导入包含 questions_info 的桌面 schema v6 配置"))
        }

        draft.definition.questions
            .filter { it.unsupported && !it.isDescription }
            .forEach { meta ->
                val typeLabel = listOf(meta.providerType, meta.typeCode).firstOrNull { it.isNotBlank() } ?: "未知类型"
                val reason = meta.unsupportedReason.ifBlank { "当前平台暂不支持该题型" }
                errors.add(
                    Issue(
                        Severity.ERROR,
                        "第 ${displayNum(meta)} 题暂不支持",
                        "${meta.title.ifBlank { "未命名题目" }}（$typeLabel，$reason）",
                        meta.num,
                    ),
                )
            }

        HttpLogicPlanner.fallbackReason(draft.definition.questions).takeIf { it.isNotBlank() }?.let { reason ->
            errors.add(Issue(Severity.ERROR, "问卷逻辑无法安全执行", reason))
        }

        val configuredNums = mutableSetOf<Int>()
        for (q in draft.questions) {
            configuredNums.add(q.num)
            val meta = metaByNum[q.num]
            if (meta == null) {
                errors.add(Issue(Severity.ERROR, "第 ${q.num} 题缺少解析元数据", "无法生成平台提交字段", q.num))
                continue
            }
            if (meta.isDescription) {
                errors.add(Issue(Severity.ERROR, "第 ${displayNum(meta)} 题是说明题", "说明题不应进入作答配置", meta.num))
                continue
            }
            if (meta.unsupported) continue

            validateQuestion(q, meta, errors, warnings)
        }

        val missingConfigured = draft.definition.questions
            .filter { it.num > 0 && !it.isDescription && !it.unsupported && it.num !in configuredNums }
        missingConfigured.take(5).forEach { meta ->
            warnings.add(
                Issue(
                    Severity.WARNING,
                    "第 ${displayNum(meta)} 题未进入配置",
                    "运行时不会主动填写：${meta.title.ifBlank { "未命名题目" }}",
                    meta.num,
                ),
            )
        }
        if (missingConfigured.size > 5) {
            warnings.add(Issue(Severity.WARNING, "还有 ${missingConfigured.size - 5} 道题未进入配置", "建议重新解析问卷结构"))
        }

        validateAnswerRules(draft, metaByNum, errors)

        if (usesAiText(draft) && !options.customAiEnabled) {
            errors.add(Issue(Severity.ERROR, "AI 填空尚未配置", "请启用并配置自定义 AI，或调整使用 AI 的题目"))
        }
        if (usesAiText(draft) && options.customAiEnabled) {
            validateCustomAiOptions(options, errors, warnings)
        }

        val p = draft.params
        validateRunParams(p, errors)
        validateAnswerDatetimeWindow(draft, errors, warnings)
        if (p.randomProxyIpEnabled && p.proxySource == "custom" && p.customProxyApi.isBlank()) {
            errors.add(Issue(Severity.ERROR, "自定义代理 API 为空", "请填写代理 API 地址，或关闭随机 IP"))
        }
        if (p.randomUserAgentEnabled && p.randomUserAgentRatios.values.sum() != 100) {
            warnings.add(Issue(Severity.WARNING, "User-Agent 占比不是 100%", "运行时会回退到默认 33/33/34"))
        }
        if (options.reverseFillEnabled) {
            if (draft.definition.provider != SurveyProviderType.WJX) {
                errors.add(
                    Issue(
                        Severity.ERROR,
                        "反向填充暂仅支持问卷星",
                        "当前问卷平台是 ${providerLabel(draft.definition.provider)}；请关闭反向填充后运行",
                    ),
                )
            }
            when {
                options.reverseFillSampleCount <= 0 ->
                    errors.add(Issue(Severity.ERROR, "反向填充没有可用样本", "请重新选择 xlsx 数据文件，或关闭反向填充"))
                options.reverseFillSampleCount < p.targetNum ->
                    errors.add(Issue(Severity.ERROR, "反向填充样本少于目标份数", "当前 ${options.reverseFillSampleCount} 份样本，目标 ${p.targetNum} 份；请降低目标份数，或把起始数据行往前调"))
            }
            val spec = options.reverseFillSpec
            if (spec == null) {
                errors.add(Issue(Severity.ERROR, "反向填充数据未完成解析", "请重新选择 xlsx 数据文件，或关闭反向填充"))
            } else {
                validateReverseFillSpec(spec, draft, errors, warnings)
            }
        } else if (draft.preserved.reverseFillEnabled && draft.preserved.reverseFillSourcePath.isNotBlank()) {
            warnings.add(
                Issue(
                    Severity.WARNING,
                    "桌面反向填充文件需重新选择",
                    "已保留 ${draft.preserved.reverseFillSourcePath}；手机端需要重新选择 xlsx 后才会启用",
                ),
            )
        }

        return Result(errors, warnings)
    }

    private fun validateReverseFillSpec(
        spec: ReverseFillSpec,
        draft: SurveyConfigDraft,
        errors: MutableList<Issue>,
        warnings: MutableList<Issue>,
    ) {
        if (spec.samples.isEmpty() || spec.totalSamples <= 0) {
            errors.add(Issue(Severity.ERROR, "反向填充没有可用样本", "起始数据行之后没有可运行样本"))
            return
        }
        val replayableAnswerCount = spec.samples.sumOf { it.answers.size }
        if (replayableAnswerCount <= 0) {
            warnings.add(
                Issue(
                    Severity.WARNING,
                    "反填样本未识别到可回放答案",
                    "当前 ${spec.totalSamples} 份样本都会使用常规配置兜底；请检查表头、起始行和识别格式",
                ),
            )
        }

        val activeQuestions = draft.definition.questions.filter { it.num > 0 && !it.isDescription && !it.unsupported }
        val activeNums = activeQuestions.map { it.num }.toSet()
        val plannedNums = spec.questionPlans.map { it.questionNum }.toSet()
        val missingPlans = activeQuestions.filter { it.num !in plannedNums }
        if (missingPlans.isNotEmpty()) {
            val names = missingPlans.take(5).joinToString("、") { "第 ${displayNum(it)} 题" }
            val suffix = if (missingPlans.size > 5) "，另有 ${missingPlans.size - 5} 题" else ""
            warnings.add(
                Issue(
                    Severity.WARNING,
                    "反填题目列未识别完整",
                    "$names$suffix 未匹配到表格列；运行时会使用常规配置兜底",
                ),
            )
        }

        val previewRows = spec.previewRows.filter { it.totalQuestions > 0 }
        val emptyRows = previewRows.filter { it.answeredQuestions <= 0 }
        if (emptyRows.isNotEmpty()) {
            warnings.add(
                Issue(
                    Severity.WARNING,
                    "反填预览存在空答案行",
                    "前 ${previewRows.size} 行中样本 ${emptyRows.take(5).joinToString("、") { it.rowNumber.toString() }} 未识别到任何题目；这些行会使用常规配置兜底",
                ),
            )
        }
        val partialRows = previewRows.filter { it.answeredQuestions in 1 until it.totalQuestions }
        if (partialRows.isNotEmpty()) {
            val worst = partialRows.minByOrNull { it.answeredQuestions.toDouble() / it.totalQuestions.toDouble() }
            val detail = worst?.let { "最低样本 ${it.rowNumber} 仅识别 ${it.answeredQuestions}/${it.totalQuestions} 题" }
                ?: "存在部分样本没有覆盖全部题目"
            warnings.add(Issue(Severity.WARNING, "反填样本存在漏题", "$detail；未命中的题会使用常规配置兜底"))
        }

        val relevantPlans = spec.questionPlans.filter { it.questionNum in activeNums }
        val degradedPlans = relevantPlans.filter { it.status != REVERSE_FILL_STATUS_REVERSE }
        if (degradedPlans.isNotEmpty()) {
            val detail = degradedPlans.take(5).joinToString("；") { plan ->
                val rows = plan.sampleRows.takeIf { it.isNotEmpty() }?.joinToString("、")?.let { "（样本 $it）" } ?: ""
                "第 ${plan.questionNum} 题${rows}: ${plan.detail.ifBlank { "无法稳定反填" }}"
            }
            val suffix = if (degradedPlans.size > 5) "；另有 ${degradedPlans.size - 5} 题" else ""
            warnings.add(Issue(Severity.WARNING, "反填题目已降级为常规配置", "$detail$suffix；这些题不会使用样本值回放"))
        }
        val fallbackPlans = relevantPlans.filter { !it.matchedByHeader }
        if (fallbackPlans.isNotEmpty()) {
            val detail = if (fallbackPlans.size == relevantPlans.size) {
                "所有 ${fallbackPlans.size} 道题都依赖列顺序匹配，表格列错位时不容易察觉"
            } else {
                val names = fallbackPlans.take(5).joinToString("、") { "第 ${it.questionNum} 题" }
                "$names 依赖列顺序匹配，建议检查表头是否与题目一致"
            }
            warnings.add(Issue(Severity.WARNING, "反填列匹配依赖顺序兜底", detail))
        }
    }

    private fun validateQuestion(
        q: QuestionConfigDraft,
        meta: SurveyQuestionMeta,
        errors: MutableList<Issue>,
        warnings: MutableList<Issue>,
    ) {
        val label = "第 ${displayNum(meta)} 题"
        validateQuestionShape(q, meta, errors)
        when (q.entryType) {
            QuestionEntryType.SINGLE, QuestionEntryType.DROPDOWN,
            QuestionEntryType.SCALE, QuestionEntryType.SCORE -> {
                if (q.usesConfiguredWeights() && countPositive(q.optionWeights) <= 0) {
                    errors.add(Issue(Severity.ERROR, "$label 配比无效", "所有选项配比都小于等于 0", meta.num))
                }
            }
            QuestionEntryType.MULTIPLE -> {
                if (!q.multiRandomCount) {
                    val positive = countPositive(q.multiProbabilities)
                    if (positive <= 0) {
                        errors.add(Issue(Severity.ERROR, "$label 多选概率无效", "请至少将 1 个选项概率设为大于 0%", meta.num))
                    }
                    val minLimit = meta.multiMinLimit?.takeIf { it > 0 }
                    if (minLimit != null && positive in 1 until minLimit) {
                        errors.add(Issue(Severity.ERROR, "$label 多选配置冲突", "题目最少选择 $minLimit 项，但只有 $positive 个选项概率大于 0%", meta.num))
                    }
                }
            }
            QuestionEntryType.MATRIX -> {
                if (q.usesConfiguredWeights()) {
                    q.matrixRowWeights.forEachIndexed { rowIndex, row ->
                        if (countPositive(row) <= 0) {
                            errors.add(Issue(Severity.ERROR, "$label 矩阵配比无效", "第 ${rowIndex + 1} 行所有选项配比都小于等于 0", meta.num))
                        }
                    }
                }
            }
            QuestionEntryType.TEXT, QuestionEntryType.MULTI_TEXT -> {
                validateTextQuestion(q, meta, errors)
            }
            QuestionEntryType.SLIDER -> {
                validateSliderQuestion(q, meta, errors)
            }
            QuestionEntryType.LOCATION -> {
                val configuredCount = q.locationParts.count { it.isNotBlank() }
                when (configuredCount) {
                    0 -> warnings.add(Issue(Severity.WARNING, "$label 地区未填写", "运行时会使用默认地区兜底", meta.num))
                    in 1..2 -> errors.add(
                        Issue(
                            Severity.ERROR,
                            "$label 地区未填完整",
                            "请填写省 / 市 / 区县三段，或全部留空使用默认地区",
                            meta.num,
                        ),
                    )
                }
            }
            else -> Unit
        }

        validateOptionFillRuntime(q, meta, warnings)
        validateAttachedOptionSelects(q, meta, errors, warnings)
    }

    private fun validateQuestionShape(
        q: QuestionConfigDraft,
        meta: SurveyQuestionMeta,
        errors: MutableList<Issue>,
    ) {
        val label = "第 ${displayNum(meta)} 题"
        val optionCount = optionCount(meta)
        when (q.entryType) {
            QuestionEntryType.SINGLE,
            QuestionEntryType.DROPDOWN,
            QuestionEntryType.SCALE,
            QuestionEntryType.SCORE -> {
                if (q.usesConfiguredWeights() && q.optionWeights.size != optionCount) {
                    errors.add(
                        Issue(
                            Severity.ERROR,
                            "$label 配比数量不一致",
                            "当前配置 ${q.optionWeights.size} 个配比，题目解析到 $optionCount 个选项；请重新解析问卷或重置本题配置",
                            meta.num,
                        ),
                    )
                }
            }
            QuestionEntryType.MULTIPLE -> {
                if (!q.multiRandomCount && q.multiProbabilities.size != optionCount) {
                    errors.add(
                        Issue(
                            Severity.ERROR,
                            "$label 多选概率数量不一致",
                            "当前配置 ${q.multiProbabilities.size} 个概率，题目解析到 $optionCount 个选项；请重新解析问卷或重置本题配置",
                            meta.num,
                        ),
                    )
                }
            }
            QuestionEntryType.MATRIX -> {
                val rowCount = matrixRowCount(meta)
                if (q.matrixRowWeights.size != rowCount) {
                    errors.add(
                        Issue(
                            Severity.ERROR,
                            "$label 矩阵行数不一致",
                            "当前配置 ${q.matrixRowWeights.size} 行，题目解析到 $rowCount 行；请重新解析问卷或重置本题配置",
                            meta.num,
                        ),
                    )
                }
                if (q.usesConfiguredWeights()) {
                    q.matrixRowWeights.forEachIndexed { rowIndex, row ->
                        if (row.size != optionCount) {
                            errors.add(
                                Issue(
                                    Severity.ERROR,
                                    "$label 矩阵列数不一致",
                                    "第 ${rowIndex + 1} 行当前 ${row.size} 个配比，题目解析到 $optionCount 个列选项；请重新解析问卷或重置本题配置",
                                    meta.num,
                                ),
                            )
                        }
                    }
                }
            }
            QuestionEntryType.TEXT,
            QuestionEntryType.MULTI_TEXT -> {
                val candidateCount = q.textCandidates.map { it.trim() }.filter { it.isNotEmpty() }.size
                if (q.textMode.trim().lowercase() == "custom" && candidateCount > 0 && q.optionWeights.size != candidateCount) {
                    errors.add(
                        Issue(
                            Severity.ERROR,
                            "$label 填空权重数量不一致",
                            "当前配置 ${q.optionWeights.size} 个权重，答案列表有 $candidateCount 条；请重新整理本题答案列表",
                            meta.num,
                        ),
                    )
                }
            }
            else -> Unit
        }
    }

    private fun validateSliderQuestion(
        q: QuestionConfigDraft,
        meta: SurveyQuestionMeta,
        errors: MutableList<Issue>,
    ) {
        if (q.distributionMode.trim().lowercase() != "custom") return
        val label = "第 ${displayNum(meta)} 题滑块目标值无效"
        val target = q.sliderTarget
        if (!target.isFinite()) {
            errors.add(Issue(Severity.ERROR, label, "请填写一个有效数字，避免运行时改为随机值", meta.num))
            return
        }
        val min = meta.sliderMin?.takeIf { it.isFinite() } ?: q.sliderMin?.takeIf { it.isFinite() } ?: 0.0
        val max = meta.sliderMax?.takeIf { it.isFinite() } ?: q.sliderMax?.takeIf { it.isFinite() } ?: 100.0
        if (max < min) {
            errors.add(Issue(Severity.ERROR, "$label", "题目解析到的滑块范围无效：$min-$max；请重新解析问卷", meta.num))
            return
        }
        if (target !in min..max) {
            errors.add(
                Issue(
                    Severity.ERROR,
                    label,
                    "当前 $target 超出题目范围 $min-$max；请改到范围内，避免运行时被截断",
                    meta.num,
                ),
            )
        }
    }

    private fun validateOptionFillRuntime(
        q: QuestionConfigDraft,
        meta: SurveyQuestionMeta,
        warnings: MutableList<Issue>,
    ) {
        if (draftProviderId(q, meta) != SurveyProviderType.QQ.id) return
        val allowed = (q.fillableOptionIndices.ifEmpty { meta.fillableOptions }).toSet()
        if (allowed.isEmpty()) return
        val hasConfiguredFill = q.optionFillTexts.withIndex().any { (index, value) ->
            index in allowed && !value.isNullOrBlank()
        }
        if (!hasConfiguredFill && q.optionFillCandidates.none { (index, choices) ->
            index in allowed && choices.any { it.isNotBlank() }
        }) return
        val label = "第 ${displayNum(meta)} 题含选项填空"
        warnings.add(
            Issue(
                Severity.WARNING,
                label,
                "腾讯问卷 HTTP 提交会按平台 blank_setting / fillblank 标记提交补充文本；请先小样本验证一次",
                meta.num,
            ),
        )
    }

    private fun validateAnswerRules(
        draft: SurveyConfigDraft,
        metaByNum: Map<Int, SurveyQuestionMeta>,
        errors: MutableList<Issue>,
    ) {
        draft.answerRules.forEachIndexed { index, raw ->
            val rule = AnswerRule.fromMap(raw)
            if (rule == null) {
                errors.add(Issue(Severity.ERROR, "第 ${index + 1} 条条件规则无效", "请删除后重新添加"))
                return@forEachIndexed
            }
            val conditionMeta = metaByNum[rule.conditionQuestionNum]
            val targetMeta = metaByNum[rule.targetQuestionNum]
            if (conditionMeta == null || targetMeta == null) {
                errors.add(
                    Issue(
                        Severity.ERROR,
                        "第 ${index + 1} 条条件规则引用了不存在的题目",
                        "请删除后重新添加",
                        rule.targetQuestionNum,
                    ),
                )
                return@forEachIndexed
            }
            if (!AnswerRule.supportsQuestion(conditionMeta) || !AnswerRule.supportsQuestion(targetMeta)) {
                errors.add(
                    Issue(
                        Severity.ERROR,
                        "第 ${index + 1} 条条件规则题型不支持",
                        "条件规则仅支持单选、多选、量表/评价和矩阵题",
                        rule.targetQuestionNum,
                    ),
                )
                return@forEachIndexed
            }
            if (rule.conditionRowIndex != null && rule.conditionRowIndex !in 0 until ruleRowCount(conditionMeta)) {
                errors.add(
                    Issue(
                        Severity.ERROR,
                        "第 ${index + 1} 条条件规则行号越界",
                        "条件题第 ${displayNum(conditionMeta)} 题没有第 ${rule.conditionRowIndex + 1} 行",
                        conditionMeta.num,
                    ),
                )
            }
            if (rule.targetRowIndex != null && rule.targetRowIndex !in 0 until ruleRowCount(targetMeta)) {
                errors.add(
                    Issue(
                        Severity.ERROR,
                        "第 ${index + 1} 条条件规则行号越界",
                        "目标题第 ${displayNum(targetMeta)} 题没有第 ${rule.targetRowIndex + 1} 行",
                        targetMeta.num,
                    ),
                )
            }
            val conditionOptionCount = ruleOptionCount(conditionMeta)
            val targetOptionCount = ruleOptionCount(targetMeta)
            val badCondition = rule.conditionOptionIndices.firstOrNull { it !in 0 until conditionOptionCount }
            if (badCondition != null) {
                errors.add(
                    Issue(
                        Severity.ERROR,
                        "第 ${index + 1} 条条件规则选项越界",
                        "条件题第 ${displayNum(conditionMeta)} 题没有第 ${badCondition + 1} 项",
                        conditionMeta.num,
                    ),
                )
            }
            val badTarget = rule.targetOptionIndices.firstOrNull { it !in 0 until targetOptionCount }
            if (badTarget != null) {
                errors.add(
                    Issue(
                        Severity.ERROR,
                        "第 ${index + 1} 条条件规则选项越界",
                        "目标题第 ${displayNum(targetMeta)} 题没有第 ${badTarget + 1} 项",
                        targetMeta.num,
                    ),
                )
            }
        }
    }

    private fun validateCustomAiOptions(
        options: Options,
        errors: MutableList<Issue>,
        warnings: MutableList<Issue>,
    ) {
        val baseUrl = options.customAiBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            errors.add(Issue(Severity.ERROR, "自定义 AI 配置不完整", "设置里已启用自定义 AI，但 Base URL 为空；请补齐或关闭自定义 AI"))
        }
        if (options.customAiApiKey.isBlank()) {
            errors.add(Issue(Severity.ERROR, "自定义 AI 配置不完整", "API Key 为空；请补齐或关闭自定义 AI"))
        }
        if (options.customAiModel.isBlank()) {
            errors.add(Issue(Severity.ERROR, "自定义 AI 配置不完整", "模型名称为空；请补齐或关闭自定义 AI"))
        }
        if (baseUrl.isBlank()) return

        val lower = baseUrl.lowercase()
        val protocol = normalizeAiApiProtocol(options.customAiApiProtocol)
        if (lower.endsWith("/completions") && !lower.endsWith("/chat/completions")) {
            errors.add(Issue(Severity.ERROR, "自定义 AI 端点协议过旧", "暂不支持旧版 /completions，请改用 /chat/completions、/responses 或只填写 /v1"))
        }
        if (lower.endsWith("/chat/completions") && protocol == "responses") {
            warnings.add(Issue(Severity.WARNING, "自定义 AI 协议选择会被端点覆盖", "Base URL 已指向 /chat/completions，运行时会按 Chat 协议请求"))
        }
        if (lower.endsWith("/responses") && protocol == "chat_completions") {
            warnings.add(Issue(Severity.WARNING, "自定义 AI 协议选择会被端点覆盖", "Base URL 已指向 /responses，运行时会按 Responses 协议请求"))
        }
    }

    private fun normalizeAiApiProtocol(raw: String?): String =
        when (raw?.trim()?.lowercase()) {
            "responses" -> "responses"
            "chat_completions" -> "chat_completions"
            else -> "auto"
        }

    private fun ruleOptionCount(meta: SurveyQuestionMeta): Int =
        maxOf(1, meta.optionTexts.size.takeIf { it > 0 } ?: meta.options)

    private fun ruleRowCount(meta: SurveyQuestionMeta): Int =
        if (meta.entryType == QuestionEntryType.MATRIX) maxOf(1, meta.rowTexts.size, meta.rows) else 1

    private fun optionCount(meta: SurveyQuestionMeta): Int =
        maxOf(1, meta.optionTexts.size.takeIf { it > 0 } ?: meta.options)

    private fun matrixRowCount(meta: SurveyQuestionMeta): Int =
        maxOf(1, meta.rowTexts.size, meta.rows)

    private fun QuestionConfigDraft.usesConfiguredWeights(): Boolean =
        distributionMode.trim().lowercase() in setOf("custom", "weighted")

    private fun validateAttachedOptionSelects(
        q: QuestionConfigDraft,
        meta: SurveyQuestionMeta,
        errors: MutableList<Issue>,
        warnings: MutableList<Issue>,
    ) {
        val configs = (q.attachedOptionSelects.takeIf { it.isNotEmpty() } ?: meta.attachedOptionSelects)
        if (configs.isEmpty()) return
        val label = "第 ${displayNum(meta)} 题"
        configs.forEachIndexed { index, config ->
            val optionCount = optionCount(meta)
            val optionIndex = config["option_index"].asIntOrNull()
            if (optionIndex == null || optionIndex !in 0 until optionCount) {
                errors.add(
                    Issue(
                        Severity.ERROR,
                        "$label 嵌入式下拉主选项越界",
                        "第 ${index + 1} 组指向的主选项不存在；题目当前只有 $optionCount 个选项",
                        meta.num,
                    ),
                )
            }
            val selectOptions = config["select_options"].asStringList()
            if (selectOptions.isEmpty()) {
                errors.add(
                    Issue(
                        Severity.ERROR,
                        "$label 嵌入式下拉缺少子选项",
                        "第 ${index + 1} 组没有可选择的子选项",
                        meta.num,
                    ),
                )
            }
            val weights = config["weights"].asNumberList()
            if (weights.isNotEmpty() && selectOptions.isNotEmpty() && weights.size != selectOptions.size) {
                errors.add(
                    Issue(
                        Severity.ERROR,
                        "$label 嵌入式下拉配比数量不一致",
                        "第 ${index + 1} 组当前 ${weights.size} 个配比，子选项有 ${selectOptions.size} 个；请重新解析问卷或重置本题配置",
                        meta.num,
                    ),
                )
            }
            if (weights.isNotEmpty() && countPositive(weights) <= 0) {
                val optionText = config["option_text"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "未命名选项"
                errors.add(
                    Issue(
                        Severity.ERROR,
                        "$label 嵌入式下拉配比无效",
                        "第 ${index + 1} 组（$optionText）所有选项配比都小于等于 0",
                        meta.num,
                    ),
                )
            }
        }
        if (q.entryType !in setOf(QuestionEntryType.SINGLE, QuestionEntryType.MULTIPLE)) {
            warnings.add(
                Issue(
                    Severity.WARNING,
                    "$label 含嵌入式下拉",
                    "已保留桌面端解析出的子下拉配置；当前运行会按主选项配置作答",
                    meta.num,
                ),
            )
        }
    }

    private fun validateAnswerDatetimeWindow(
        draft: SurveyConfigDraft,
        errors: MutableList<Issue>,
        warnings: MutableList<Issue>,
    ) {
        val p = draft.params
        val startText = p.answerDatetimeStart.trim()
        val endText = p.answerDatetimeEnd.trim()
        if (startText.isEmpty() && endText.isEmpty()) return

        if (draft.definition.provider != SurveyProviderType.CREDAMO) {
            warnings.add(
                Issue(
                    Severity.WARNING,
                    "当前平台不使用作答时间窗",
                    "只有见数问卷会按开始/结束日期时间安排作答，当前配置将被忽略",
                ),
            )
            return
        }

        if (startText.isEmpty() || endText.isEmpty()) {
            errors.add(Issue(Severity.ERROR, "见数作答时间窗未配完整", "请同时设置开始和结束日期时间"))
            return
        }

        val start = parseAnswerDateTime(startText)
        val end = parseAnswerDateTime(endText)
        if (start == null || end == null) {
            errors.add(Issue(Severity.ERROR, "见数作答时间窗格式无效", "请使用 yyyy-MM-dd HH:mm 或 yyyy-MM-dd HH:mm:ss"))
            return
        }
        if (!end.isAfter(start)) {
            errors.add(Issue(Severity.ERROR, "见数结束日期时间必须晚于开始日期时间"))
            return
        }

        val windowSeconds = java.time.Duration.between(start, end).seconds
        val maxDurationSeconds = p.answerDurationMax.coerceAtLeast(0).toLong()
        if (windowSeconds < maxDurationSeconds) {
            errors.add(Issue(Severity.ERROR, "见数作答时间窗太窄", "容不下当前最长作答时长 ${maxDurationSeconds} 秒"))
        }
    }

    private fun validateRunParams(
        p: RunParamsDraft,
        errors: MutableList<Issue>,
    ) {
        if (p.targetNum <= 0) {
            errors.add(Issue(Severity.ERROR, "目标份数无效", "目标份数必须大于 0"))
        }
        if (p.numThreads !in 1..MaxThreads) {
            errors.add(Issue(Severity.ERROR, "并发线程数超出范围", "当前 ${p.numThreads}，支持范围为 1-$MaxThreads"))
        }
        if (p.failThreshold <= 0) {
            errors.add(Issue(Severity.ERROR, "连续失败阈值无效", "连续失败阈值必须大于 0"))
        }
        if (!p.psychoTargetAlpha.isFinite() || p.psychoTargetAlpha !in 0.60..0.95) {
            errors.add(Issue(Severity.ERROR, "信度目标 Alpha 无效", "请设置 0.60-0.95 之间的数值，避免运行时被静默截断"))
        }
        when {
            p.answerDurationMin <= 0 || p.answerDurationMax <= 0 ->
                errors.add(Issue(Severity.ERROR, "作答时长必须大于 0", "请设置有效的单份作答时长区间"))
            p.answerDurationMin > p.answerDurationMax ->
                errors.add(Issue(Severity.ERROR, "作答时长区间无效", "下限不能大于上限"))
            p.answerDurationMax > MaxAnswerDurationSeconds ->
                errors.add(Issue(Severity.ERROR, "作答时长超出上限", "最长支持 ${MaxAnswerDurationSeconds} 秒，避免运行时被静默截断"))
        }
        when {
            p.submitIntervalMin < 0 || p.submitIntervalMax < 0 ->
                errors.add(Issue(Severity.ERROR, "提交间隔不能为负", "请设置 0-${MaxSubmitIntervalSeconds} 秒内的提交间隔"))
            p.submitIntervalMin > p.submitIntervalMax ->
                errors.add(Issue(Severity.ERROR, "提交间隔区间无效", "下限不能大于上限"))
            p.submitIntervalMax > MaxSubmitIntervalSeconds ->
                errors.add(Issue(Severity.ERROR, "提交间隔超出上限", "最长支持 ${MaxSubmitIntervalSeconds} 秒，避免运行时被静默截断"))
        }
    }

    private fun parseAnswerDateTime(raw: String): LocalDateTime? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        for (format in answerDatetimeFormats) {
            try {
                return LocalDateTime.parse(text, format)
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    private fun validateTextQuestion(
        q: QuestionConfigDraft,
        meta: SurveyQuestionMeta,
        errors: MutableList<Issue>,
    ) {
        if (isTextAiEnabled(q)) return
        validateIntegerTextRanges(q, meta, errors)
        validateCustomTextAnswers(q, meta, errors)
        val minLength = extractTextMinLength(meta.title, meta.description) ?: return
        if (minLength <= 0) return
        val label = "第 ${displayNum(meta)} 题填空配置冲突"
        if (q.entryType == QuestionEntryType.TEXT && q.textMode != "custom") {
            errors.add(Issue(Severity.ERROR, label, "题目要求最少 $minLength 字，当前随机模式无法保证字数", meta.num))
            return
        }
        val tooShort = q.textCandidates.mapIndexedNotNull { index, answer ->
            val parts = if (q.entryType == QuestionEntryType.MULTI_TEXT) {
                TextValues.splitMultiTextCandidate(answer)
            } else {
                listOf(answer.trim())
            }.filter { it.isNotEmpty() }
            val shortPart = parts.firstOrNull { it.length < minLength } ?: return@mapIndexedNotNull null
            (index + 1) to shortPart.length
        }
        if (tooShort.isNotEmpty()) {
            val detail = tooShort.take(5).joinToString("、") { "第 ${it.first} 个答案 ${it.second} 字" }
            errors.add(Issue(Severity.ERROR, label, "题目要求最少 $minLength 字，但 $detail", meta.num))
        }
    }

    private fun validateIntegerTextRanges(
        q: QuestionConfigDraft,
        meta: SurveyQuestionMeta,
        errors: MutableList<Issue>,
    ) {
        if (q.entryType == QuestionEntryType.TEXT && q.textMode.trim().lowercase() == "integer") {
            if (q.textIntMin > q.textIntMax) {
                errors.add(
                    Issue(
                        Severity.ERROR,
                        "第 ${displayNum(meta)} 题随机整数范围无效",
                        "最小值 ${q.textIntMin} 不能大于最大值 ${q.textIntMax}，避免运行时自动调换范围",
                        meta.num,
                    ),
                )
            }
            return
        }

        if (q.entryType != QuestionEntryType.MULTI_TEXT) return
        val blankCount = multiTextBlankCount(q, meta)
        val invalid = (0 until blankCount).mapNotNull { idx ->
            if (!q.isIntegerMultiTextBlank(idx)) return@mapNotNull null
            val range = q.multiTextBlankIntRanges.getOrNull(idx)
            val min = range?.getOrNull(0) ?: 0
            val max = range?.getOrNull(1) ?: 100
            if (min <= max) null else "${multiTextBlankLabel(q, idx)} $min-$max"
        }
        if (invalid.isNotEmpty()) {
            errors.add(
                Issue(
                    Severity.ERROR,
                    "第 ${displayNum(meta)} 题多项填空随机整数范围无效",
                    "${invalid.take(5).joinToString("、")}；最小值不能大于最大值，避免运行时自动调换范围",
                    meta.num,
                ),
            )
        }
    }

    private fun validateCustomTextAnswers(
        q: QuestionConfigDraft,
        meta: SurveyQuestionMeta,
        errors: MutableList<Issue>,
    ) {
        if (q.textMode.trim().lowercase() != "custom") return
        when (q.entryType) {
            QuestionEntryType.TEXT -> {
                if (q.textCandidates.none { it.trim().isNotEmpty() }) {
                    errors.add(
                        Issue(
                            Severity.ERROR,
                            "第 ${displayNum(meta)} 题填空答案为空",
                            "请至少填写 1 条候选答案，或切换为随机/AI 模式，避免运行时提交默认文本",
                            meta.num,
                        ),
                    )
                }
            }
            QuestionEntryType.MULTI_TEXT -> validateCustomMultiTextAnswers(q, meta, errors)
            else -> Unit
        }
    }

    private fun validateCustomMultiTextAnswers(
        q: QuestionConfigDraft,
        meta: SurveyQuestionMeta,
        errors: MutableList<Issue>,
    ) {
        val blankCount = multiTextBlankCount(q, meta)
        val customBlankIndexes = (0 until blankCount).filter { idx -> q.isCustomMultiTextBlank(idx) }
        if (customBlankIndexes.isEmpty()) return

        val rows = q.textCandidates.mapIndexedNotNull { index, raw ->
            val parts = TextValues.splitMultiTextCandidate(raw)
            if (parts.any { it.trim().isNotEmpty() }) IndexedMultiTextCandidate(index + 1, parts) else null
        }
        if (rows.isEmpty()) {
            errors.add(
                Issue(
                    Severity.ERROR,
                    "第 ${displayNum(meta)} 题多项填空答案为空",
                    "请至少填写 1 组候选答案，或将自定义空切换为随机/AI 模式",
                    meta.num,
                ),
            )
            return
        }

        val incomplete = rows.mapNotNull { row ->
            val missingIndex = customBlankIndexes.firstOrNull { idx ->
                row.parts.getOrNull(idx)?.trim().isNullOrEmpty()
            } ?: return@mapNotNull null
            "第 ${row.number} 组缺少${multiTextBlankLabel(q, missingIndex)}"
        }
        if (incomplete.isNotEmpty()) {
            val detail = incomplete.take(5).joinToString("、")
            errors.add(
                Issue(
                    Severity.ERROR,
                    "第 ${displayNum(meta)} 题多项填空答案不完整",
                    "$detail；请补齐自定义空，或切换为随机/AI 模式",
                    meta.num,
                ),
            )
        }
    }

    private data class IndexedMultiTextCandidate(
        val number: Int,
        val parts: List<String>,
    )

    private fun multiTextBlankCount(q: QuestionConfigDraft, meta: SurveyQuestionMeta): Int =
        maxOf(1, meta.textInputs, q.textInputLabels.size, q.multiTextBlankModes.size, q.multiTextBlankAiFlags.size)

    private fun QuestionConfigDraft.isCustomMultiTextBlank(index: Int): Boolean {
        if (multiTextBlankAiFlags.getOrNull(index) == true) return false
        return when (multiTextBlankModes.getOrNull(index)?.trim()?.lowercase()) {
            "name", TextValues.MODE_RANDOM_NAME,
            "mobile", TextValues.MODE_RANDOM_MOBILE,
            "id_card", TextValues.MODE_RANDOM_ID_CARD,
            "integer", TextValues.MODE_RANDOM_INTEGER -> false
            else -> true
        }
    }

    private fun QuestionConfigDraft.isIntegerMultiTextBlank(index: Int): Boolean {
        if (multiTextBlankAiFlags.getOrNull(index) == true) return false
        return multiTextBlankModes.getOrNull(index)?.trim()?.lowercase() in setOf(
            "integer",
            TextValues.MODE_RANDOM_INTEGER,
        )
    }

    private fun multiTextBlankLabel(q: QuestionConfigDraft, index: Int): String =
        q.textInputLabels.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "「$it」" }
            ?: "第 ${index + 1} 个空"

    private fun isTextAiEnabled(q: QuestionConfigDraft): Boolean {
        if (q.useAiText) return true
        if (q.entryType != QuestionEntryType.MULTI_TEXT) return false
        val blankCount = maxOf(1, q.textInputLabels.size, q.multiTextBlankAiFlags.size)
        return blankCount > 0 && (0 until blankCount).all { q.multiTextBlankAiFlags.getOrNull(it) == true }
    }

    private fun usesAiText(draft: SurveyConfigDraft): Boolean =
        draft.questions.any { question ->
            question.useAiText || question.multiTextBlankAiFlags.any { it } ||
                question.optionFillTexts.any { it?.trim() == OPTION_FILL_AI_TOKEN } ||
                question.optionFillCandidates.values.flatten().any { it.trim() == OPTION_FILL_AI_TOKEN }
        }

    private fun countPositive(weights: Iterable<Double>): Int =
        weights.count { it.isFinite() && it > 0.0 }

    private fun draftProviderId(q: QuestionConfigDraft, meta: SurveyQuestionMeta): String =
        q.surveyProvider.ifBlank { meta.provider }.trim().lowercase()

    private fun Any?.asNumberList(): List<Double> {
        val raw = this ?: return emptyList()
        val values = when (raw) {
            is Iterable<*> -> raw.toList()
            is Array<*> -> raw.toList()
            else -> return emptyList()
        }
        return values.mapNotNull { item ->
            when (item) {
                is Number -> item.toDouble()
                else -> item?.toString()?.toDoubleOrNull()
            }
        }
    }

    private fun Any?.asIntOrNull(): Int? = when (this) {
        is Number -> toInt()
        else -> this?.toString()?.trim()?.toIntOrNull()
    }

    private fun Any?.asStringList(): List<String> = when (this) {
        is Iterable<*> -> map { it?.toString()?.trim().orEmpty() }.filter { it.isNotEmpty() }
        is Array<*> -> map { it?.toString()?.trim().orEmpty() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun extractTextMinLength(vararg fragments: String): Int? {
        val values = mutableListOf<Int>()
        for (fragment in fragments) {
            val text = fragment.trim()
            if (text.isEmpty()) continue
            for (pattern in textMinLengthPatterns) {
                pattern.findAll(text).forEach { match ->
                    match.groupValues.getOrNull(1)?.toIntOrNull()?.let { values.add(it) }
                }
            }
        }
        return values.maxOrNull()
    }

    private fun displayNum(meta: SurveyQuestionMeta): Any =
        meta.displayNum ?: meta.num

    private fun providerLabel(provider: SurveyProviderType): String =
        when (provider) {
            SurveyProviderType.WJX -> "问卷星"
            SurveyProviderType.QQ -> "腾讯问卷"
            SurveyProviderType.CREDAMO -> "见数"
        }
}
