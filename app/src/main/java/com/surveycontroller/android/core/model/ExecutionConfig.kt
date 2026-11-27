package com.surveycontroller.android.core.model

/**
 * 一次任务启动前固定下来的静态执行配置。
 * 对应 Python 端 software/core/task/task_context.py:ExecutionConfig。
 *
 * 概率数组按"配置索引"组织（同一题型的题目顺序排列），由 question_config_index_map 建立
 * 题号 → (题型, 配置索引) 的映射。
 */
data class ExecutionConfig(
    val url: String = "",
    val surveyTitle: String = "",
    val surveyProvider: String = "wjx",

    // ===== 各题型的答案概率/权重配置（按配置索引）=====
    val singleProb: List<Any?> = emptyList(),          // 单选：List<Double> 权重 或 标量
    val droplistProb: List<Any?> = emptyList(),        // 下拉
    val multipleProb: List<List<Double>> = emptyList(),// 多选：每选项命中概率(0-100)，-1 表示随机数量
    val matrixProb: List<Any?> = emptyList(),          // 矩阵：按行展开
    val scaleProb: List<Any?> = emptyList(),           // 量表
    val sliderTargets: List<Double> = emptyList(),     // 滑块目标值；NaN 表示随机
    val texts: List<List<String>> = emptyList(),       // 填空候选文本
    val textsProb: List<List<Double>> = emptyList(),   // 填空文本权重
    val textEntryTypes: List<String> = emptyList(),
    val textAiFlags: List<Boolean> = emptyList(),
    val textTitles: List<String> = emptyList(),
    val locationParts: Map<Int, List<String>> = emptyMap(),
    val multiTextBlankModes: List<List<String>> = emptyList(),
    val multiTextBlankAiFlags: List<List<Boolean>> = emptyList(),
    val multiTextBlankIntRanges: List<List<List<Int>>> = emptyList(),
    val singleOptionFillTexts: List<List<String?>?> = emptyList(),
    val singleAttachedOptionSelects: List<List<Map<String, Any?>>> = emptyList(),
    val droplistOptionFillTexts: List<List<String?>?> = emptyList(),
    val multipleOptionFillTexts: List<List<String?>?> = emptyList(),
    val optionFillCandidates: Map<Int, Map<Int, List<String>>> = emptyMap(),
    val multipleAttachedOptionSelects: List<List<Map<String, Any?>>> = emptyList(),
    val answerRules: List<Map<String, Any?>> = emptyList(),

    // ===== 映射表 =====
    val questionConfigIndexMap: Map<Int, Pair<String, Int>> = emptyMap(),
    val providerQuestionConfigIndexMap: Map<String, Pair<String, Int>> = emptyMap(),
    val providerQuestionConfigNumMap: Map<String, Int> = emptyMap(),
    val questionDimensionMap: Map<Int, String?> = emptyMap(),
    val questionOrdinalScoreMap: Map<Int, List<Int>> = emptyMap(),
    val questionStrictRatioMap: Map<Int, Boolean> = emptyMap(),
    val questionPsychoBiasMap: Map<Int, Any?> = emptyMap(),
    val questionsMetadata: Map<Int, SurveyQuestionMeta> = emptyMap(),

    val psychoTargetAlpha: Double = 0.85,

    // ===== 运行参数 =====
    val numThreads: Int = 1,
    val targetNum: Int = 1,
    val failThreshold: Int = 5,
    val stopOnFailEnabled: Boolean = true,
    val submitEnabled: Boolean = true,
    val reliabilityModeEnabled: Boolean = true,
    val submissionReportEnabled: Boolean = true,

    val submitIntervalRangeSeconds: IntRange = 0..0,
    val answerDurationRangeSeconds: IntRange = 0..0,
    val answerDatetimeWindowMs: LongRange = 0L..0L,

    // ===== 网络 / 代理 / UA =====
    val randomProxyIpEnabled: Boolean = false,
    val proxySource: String = "default",
    val customProxyApi: String = "",
    val proxyAreaCode: String? = null,
    val randomUserAgentEnabled: Boolean = false,
    val userAgentRatios: Map<String, Int> = mapOf("wechat" to 33, "mobile" to 33, "pc" to 34),
    val pauseOnAliyunCaptcha: Boolean = true,

    // ===== AI =====
    val aiMode: String = "provider",
    val aiSystemPrompt: String = "",
    val aiEnabled: Boolean = false,
    val aiBaseUrl: String = "",
    val aiApiKey: String = "",
    val aiModel: String = "",
    val aiApiProtocol: String = "auto",
) {
    companion object {
        fun providerQuestionKey(provider: String?, providerPageId: String?, providerQuestionId: String?): String {
            val normalizedProvider = provider?.trim()?.lowercase()?.takeIf { it in setOf("wjx", "qq", "credamo") } ?: "wjx"
            val pageId = providerPageId?.trim().orEmpty()
            val questionId = providerQuestionId?.trim().orEmpty()
            if (pageId.isEmpty() || questionId.isEmpty()) return ""
            return "$normalizedProvider:$pageId:$questionId"
        }
    }

    /** 按页、题号排序后的题目列表，用于提交。 */
    fun orderedQuestions(): List<SurveyQuestionMeta> =
        questionsMetadata.values.sortedWith(compareBy({ it.page }, { it.num }))

    fun providerQuestionKey(question: SurveyQuestionMeta): String =
        providerQuestionKey(
            question.provider.ifBlank { surveyProvider },
            question.providerPageId,
            question.providerQuestionId,
        )

    fun configEntryFor(question: SurveyQuestionMeta): Pair<String, Int>? {
        val providerKey = providerQuestionKey(question)
        if (providerKey.isNotEmpty()) {
            providerQuestionConfigIndexMap[providerKey]?.let { return it }
        }
        return questionConfigIndexMap[question.num]
    }

    fun configQuestionNumFor(question: SurveyQuestionMeta): Int {
        val providerKey = providerQuestionKey(question)
        if (providerKey.isNotEmpty()) {
            providerQuestionConfigNumMap[providerKey]?.let { return it }
        }
        return question.num
    }
}
