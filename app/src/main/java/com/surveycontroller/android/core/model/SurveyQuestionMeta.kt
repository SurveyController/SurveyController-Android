package com.surveycontroller.android.core.model

/**
 * 标准化后的单题元数据。对应 Python 端 SurveyQuestionMeta / 解析阶段产出的 question dict。
 * 字段命名保持与原项目语义一致，便于协议复刻时对照。
 */
data class SurveyQuestionMeta(
    val num: Int = 0,                       // 题号（提交用，1-based）
    val displayNum: Int? = null,            // 展示题号
    val title: String = "",                 // 题干
    val typeCode: String = "0",             // 问卷星原生 type
    val page: Int = 1,                      // 所在页（fieldset）
    // ===== 平台原生标识（腾讯/credamo 用）=====
    val provider: String = "",
    val providerQuestionId: String = "",
    val providerPageId: String = "",
    val providerType: String = "",
    val providerPageRaw: Map<String, Any?>? = null,
    val options: Int = 0,                   // 选项数 / 列数
    val rows: Int = 1,                      // 矩阵行数
    val optionTexts: List<String> = emptyList(),
    val rowTexts: List<String> = emptyList(),
    val required: Boolean = false,
    val isRating: Boolean = false,
    val ratingMax: Int = 0,
    val isDescription: Boolean = false,
    val isLocation: Boolean = false,
    val isTextLike: Boolean = false,
    val isMultiText: Boolean = false,
    val isSliderMatrix: Boolean = false,
    val textInputs: Int = 0,
    val textInputLabels: List<String> = emptyList(),
    val fillableOptions: List<Int> = emptyList(),   // 含"其他___"填空的选项索引
    val forcedOptionIndex: Int? = null,             // "请选XX"强制项
    val forcedOptionText: String? = null,
    val forcedTexts: List<String> = emptyList(),    // "请填写XX"强制文本
    val attachedOptionSelects: List<Map<String, Any?>> = emptyList(),
    val hasAttachedOptionSelect: Boolean = false,
    val multiMinLimit: Int? = null,                 // 多选最少
    val multiMaxLimit: Int? = null,                 // 多选最多
    val sliderMin: Double? = null,
    val sliderMax: Double? = null,
    val sliderStep: Double? = null,
    val hasJump: Boolean = false,
    val jumpRules: List<JumpRule> = emptyList(),
    val hasDisplayCondition: Boolean = false,
    val displayConditions: List<DisplayCondition> = emptyList(),
    val hasDependentDisplayLogic: Boolean = false,
    val controlsDisplayTargets: List<DisplayTarget> = emptyList(),
    val logicParseStatus: String = "none",
    val questionMedia: List<QuestionMedia> = emptyList(),
    val unsupported: Boolean = false,
    val unsupportedReason: String = "",
    val description: String = "",
) {
    val entryType: QuestionEntryType get() = QuestionEntryType.infer(this)
}

/** 跳题规则：选中 optionIndex（-1=无条件）跳到 targetQuestion；terminates 表示结束作答。 */
data class JumpRule(
    val optionIndex: Int,
    val targetQuestion: Int?,
    val optionText: String? = null,
    val terminates: Boolean = false,
)

/** 显隐条件：当来源题 conditionQuestionNum 以 mode 命中指定选项时，本题可见。 */
data class DisplayCondition(
    val conditionQuestionNum: Int,
    val conditionMode: String = "selected",      // selected / not_selected
    val conditionOptionIndices: List<Int> = emptyList(),
)

/** 控制显示目标：本题选中条件选项时控制 targetQuestionNum 的显示。 */
data class DisplayTarget(
    val targetQuestionNum: Int,
    val conditionMode: String = "selected",
    val conditionOptionIndices: List<Int> = emptyList(),
)

/** 题目/选项/矩阵行附带媒体。当前先保存图片元数据，供配置页提示与后续预览使用。 */
data class QuestionMedia(
    val kind: String = "image",
    val scope: String,
    val index: Int? = null,
    val sourceUrl: String,
    val label: String = "",
)
