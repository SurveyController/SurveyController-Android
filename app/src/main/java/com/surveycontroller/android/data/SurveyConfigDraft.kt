package com.surveycontroller.android.data

import com.surveycontroller.android.core.model.QuestionEntryType
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.provider.SurveyDefinition

/**
 * 单题的可编辑配置（供配置向导 UI 调整）。
 */
data class QuestionConfigDraft(
    val num: Int,
    val title: String,
    val entryType: QuestionEntryType,
    val optionTexts: List<String>,
    val rowTexts: List<String>,
    val surveyProvider: String = "",
    val providerQuestionId: String = "",
    val providerPageId: String = "",
    // 单选/下拉/量表：各选项权重（默认均分）
    val optionWeights: MutableList<Double>,
    // 多选：各选项命中概率 0-100（默认 50），-1 表示随机数量
    val multiProbabilities: MutableList<Double>,
    val multiRandomCount: Boolean = false,
    // 矩阵：每行各列权重
    val matrixRowWeights: MutableList<MutableList<Double>>,
    // 填空候选文本
    val textCandidates: MutableList<String>,
    val useAiText: Boolean = false,
    // 填空模式：custom(自定义文本)/name/mobile/id_card/integer/generic
    val textMode: String = "custom",
    val textIntMin: Int = 0,
    val textIntMax: Int = 100,
    // 选项填空："其他____"这类选项的补充文本，按选项索引对齐
    val fillableOptionIndices: List<Int> = emptyList(),
    val optionFillTexts: MutableList<String?> = mutableListOf(),
    // 单选选项内嵌下拉配置。Android 当前先保真/校验，与桌面 schema v6 对齐。
    val attachedOptionSelects: List<Map<String, Any?>> = emptyList(),
    // 多项填空：每个空的独立模式和随机整数范围
    val textInputLabels: List<String> = emptyList(),
    val multiTextBlankModes: MutableList<String> = mutableListOf(),
    val multiTextBlankAiFlags: MutableList<Boolean> = mutableListOf(),
    val multiTextBlankIntRanges: MutableList<MutableList<Int>> = mutableListOf(),
    // 地区题：省 / 市 / 区县，三段全空时运行时用默认地区兜底
    val locationParts: MutableList<String> = mutableListOf(),
    // 滑块目标值
    val sliderTarget: Double = 50.0,
    val sliderMin: Double? = null,
    val sliderMax: Double? = null,
    // 信度维度（同维度量表题保持一致性）；空表示不分组
    val dimension: String? = null,
    // 配比模式：random(完全随机/等概率) / weighted(按权重随机) / custom(自定义配比，运行时严格收敛到设定比例)
    val distributionMode: String = "random",
    // 倾向预设：custom/left/center/right（仅 UI 一键应用，落到 optionWeights）
    val biasPreset: String = "custom",
    // 矩阵题每行的信度倾向预设；对齐桌面端 question_entries.psycho_bias 的列表形态。
    val matrixBiasPresets: MutableList<String> = mutableListOf(),
    // 占比滑块中被锁定的选项（拖动其他项时保持不变）；纯 UI 辅助，不影响提交
    val lockedOptions: Set<Int> = emptySet(),
    val questionId: String = "",
    val optionFillCandidates: Map<Int, List<String>> = emptyMap(),
)

/**
 * 运行参数草稿（份数/并发/时长/随机IP/UA 等）。
 */
data class RunParamsDraft(
    val targetNum: Int = 1,
    val numThreads: Int = 1,
    val answerDurationMin: Int = 60,
    val answerDurationMax: Int = 120,
    val submitIntervalMin: Int = 0,
    val submitIntervalMax: Int = 0,
    val answerDatetimeStart: String = "",
    val answerDatetimeEnd: String = "",
    val randomProxyIpEnabled: Boolean = false,
    val proxySource: String = "default",
    val customProxyApi: String = "",
    val proxyAreaCode: String? = null,
    val randomUserAgentEnabled: Boolean = false,
    val randomUserAgentRatios: Map<String, Int> = mapOf("wechat" to 33, "mobile" to 33, "pc" to 34),
    val stopOnFailEnabled: Boolean = true,
    val failThreshold: Int = 5,
    val reliabilityModeEnabled: Boolean = true,
    val psychoTargetAlpha: Double = 0.85,
    val pauseOnAliyunCaptcha: Boolean = true,
    val aiMode: String = "provider",
)

data class ImportedAiConfig(
    val present: Boolean = false,
    val mode: String = "provider",
    val provider: String = "deepseek",
    val apiKey: String = "",
    val baseUrl: String = "",
    val apiProtocol: String = "auto",
    val model: String = "",
    val systemPrompt: String = "",
) {
    val isProviderMode: Boolean get() = mode.trim().lowercase() == "provider"
}

/**
 * Android 当前不直接编辑、但需要在桌面 schema v6 间导入/导出时保真的字段。
 */
data class ConfigPreservedFields(
    val dimensionGroups: List<String> = emptyList(),
    val reverseFillEnabled: Boolean = false,
    val reverseFillSourcePath: String = "",
    val reverseFillFormat: String = "auto",
    val reverseFillStartRow: Int = 1,
    val reverseFillThreads: Int = 1,
    val importedAiConfig: ImportedAiConfig = ImportedAiConfig(),
)

/**
 * 整体配置草稿：问卷定义 + 各题配置 + 运行参数。
 */
data class SurveyConfigDraft(
    val definition: SurveyDefinition,
    val questions: List<QuestionConfigDraft>,
    val params: RunParamsDraft = RunParamsDraft(),
    val answerRules: List<Map<String, Any?>> = emptyList(),
    val preserved: ConfigPreservedFields = ConfigPreservedFields(),
) {
    companion object {
        fun fromDefinition(def: SurveyDefinition): SurveyConfigDraft {
            val drafts = def.questions.mapNotNull { q -> buildDraft(q, def.provider.id) }
            return SurveyConfigDraft(def, drafts)
        }

        private fun buildDraft(q: SurveyQuestionMeta, providerId: String): QuestionConfigDraft? {
            if (q.isDescription || q.unsupported) return null
            val entry = q.entryType
            val optionCount = maxOf(1, q.optionTexts.size.takeIf { it > 0 } ?: q.options)
            val blankCount = maxOf(1, q.textInputs)
            val forcedTextCandidates = q.forcedTexts.map { it.trim() }.filter { it.isNotEmpty() }
            val defaultTextCandidates = forcedTextCandidates.toMutableList().ifEmpty { mutableListOf("已填写") }
            val forcedOptionWeights = forcedOptionWeights(entry, q.forcedOptionIndex, optionCount)
            return QuestionConfigDraft(
                num = q.num,
                title = q.title,
                entryType = entry,
                optionTexts = q.optionTexts,
                rowTexts = q.rowTexts,
                surveyProvider = q.provider.ifBlank { providerId },
                providerQuestionId = q.providerQuestionId,
                providerPageId = q.providerPageId,
                questionId = q.questionId,
                optionWeights = forcedOptionWeights
                    ?: evenPercents(if (entry == QuestionEntryType.TEXT || entry == QuestionEntryType.MULTI_TEXT) defaultTextCandidates.size else optionCount),
                multiProbabilities = MutableList(optionCount) { 50.0 },
                matrixRowWeights = MutableList(maxOf(1, q.rows)) { evenPercents(optionCount) },
                textCandidates = defaultTextCandidates,
                fillableOptionIndices = q.fillableOptions.filter { it in 0 until optionCount },
                optionFillTexts = MutableList(optionCount) { null },
                attachedOptionSelects = q.attachedOptionSelects,
                textInputLabels = q.textInputLabels,
                multiTextBlankModes = MutableList(blankCount) { idx ->
                    inferBlankMode(q.textInputLabels.getOrNull(idx), q.title, blankCount)
                },
                multiTextBlankAiFlags = MutableList(blankCount) { false },
                multiTextBlankIntRanges = MutableList(blankCount) { mutableListOf(0, 100) },
                locationParts = MutableList(3) { "" },
                sliderTarget = defaultSliderTarget(q.sliderMin, q.sliderMax),
                sliderMin = q.sliderMin,
                sliderMax = q.sliderMax,
                distributionMode = if (forcedOptionWeights != null) "custom" else "random",
                matrixBiasPresets = MutableList(maxOf(1, q.rows)) { "custom" },
            )
        }

        /** 把 N 项按百分比均分到总和=100（首项补差），用于占比初始化。 */
        private fun evenPercents(n: Int): MutableList<Double> {
            if (n <= 0) return mutableListOf()
            val each = 100 / n
            return MutableList(n) { if (it < n - 1) each.toDouble() else (100 - each * (n - 1)).toDouble() }
        }

        private fun forcedOptionWeights(entry: QuestionEntryType, rawIndex: Int?, optionCount: Int): MutableList<Double>? {
            if (entry !in setOf(QuestionEntryType.SINGLE, QuestionEntryType.DROPDOWN, QuestionEntryType.SCALE, QuestionEntryType.SCORE)) {
                return null
            }
            val count = maxOf(1, optionCount)
            val index = rawIndex?.takeIf { it in 0 until count } ?: return null
            return MutableList(count) { idx -> if (idx == index) 100.0 else 0.0 }
        }

        private fun defaultSliderTarget(min: Double?, max: Double?): Double {
            val lo = min ?: 0.0
            val hi = max ?: 100.0
            return if (hi > lo) lo + (hi - lo) / 2.0 else 50.0
        }

        private fun inferBlankMode(label: String?, title: String, blankCount: Int): String {
            val source = (label?.takeIf { it.isNotBlank() } ?: if (blankCount <= 1) title else "")
            val text = source.filterNot { it.isWhitespace() }.lowercase()
            return when {
                listOf("手机号", "手机号码", "手机", "电话", "联系电话", "联系方式").any { it in text } -> "mobile"
                listOf("身份证", "证件号", "证件号码").any { it in text } -> "id_card"
                listOf("姓名", "名字", "联系人").any { it in text } -> "name"
                listOf("年龄", "年纪", "岁数", "数量", "个数", "人数", "次数", "金额", "收入", "分数", "时长", "分钟", "小时", "天数").any { it in text } -> "integer"
                else -> "custom"
            }
        }
    }
}
