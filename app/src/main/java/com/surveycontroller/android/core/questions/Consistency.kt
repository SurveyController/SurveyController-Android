package com.surveycontroller.android.core.questions

import com.surveycontroller.android.core.model.AnswerAction
import com.surveycontroller.android.core.model.QuestionEntryType
import com.surveycontroller.android.core.model.SurveyQuestionMeta

/**
 * 条件规则：当条件题以某模式命中选项时，约束目标题必选/禁选某些选项。
 * 1:1 复刻 software/core/questions/consistency.py。
 */
data class AnswerRule(
    val id: String,
    val conditionQuestionNum: Int,
    val conditionMode: String,                 // selected / not_selected
    val conditionOptionIndices: List<Int>,
    val targetQuestionNum: Int,
    val actionMode: String,                    // must_select / must_not_select
    val targetOptionIndices: List<Int>,
    val conditionRowIndex: Int? = null,
    val targetRowIndex: Int? = null,
) {
    companion object {
        private val CONDITION_MODES = setOf("selected", "not_selected")
        private val ACTION_MODES = setOf("must_select", "must_not_select")
        private val SUPPORTED_ENTRY_TYPES = setOf(
            QuestionEntryType.SINGLE,
            QuestionEntryType.MULTIPLE,
            QuestionEntryType.SCALE,
            QuestionEntryType.SCORE,
            QuestionEntryType.MATRIX,
        )

        private fun toIntList(values: Any?): List<Int> {
            val list = values as? List<*> ?: return emptyList()
            val seen = LinkedHashSet<Int>()
            for (item in list) {
                val v = (item as? Number)?.toInt() ?: item?.toString()?.trim()?.toIntOrNull() ?: continue
                if (v >= 0) seen.add(v)
            }
            return seen.sorted()
        }

        /** 从原始 Map 规范化规则。复刻 normalize_rule_dict。 */
        fun fromMap(raw: Map<String, Any?>): AnswerRule? {
            val condQ = (raw["condition_question_num"] as? Number)?.toInt()
                ?: raw["condition_question_num"]?.toString()?.toIntOrNull() ?: -1
            val targetQ = (raw["target_question_num"] as? Number)?.toInt()
                ?: raw["target_question_num"]?.toString()?.toIntOrNull() ?: -1
            val condMode = raw["condition_mode"]?.toString()?.trim().orEmpty()
            val actMode = raw["action_mode"]?.toString()?.trim().orEmpty()
            if (condQ <= 0 || targetQ <= 0) return null
            if (condMode !in CONDITION_MODES || actMode !in ACTION_MODES) return null
            val condOpts = toIntList(raw["condition_option_indices"])
            val targetOpts = toIntList(raw["target_option_indices"])
            if (condOpts.isEmpty() || targetOpts.isEmpty()) return null
            val condRow = ((raw["condition_row_index"] as? Number)?.toInt()
                ?: raw["condition_row_index"]?.toString()?.trim()?.toIntOrNull())?.takeIf { it >= 0 }
            val targetRow = ((raw["target_row_index"] as? Number)?.toInt()
                ?: raw["target_row_index"]?.toString()?.trim()?.toIntOrNull())?.takeIf { it >= 0 }
            val id = raw["id"]?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "rule-$condQ-$targetQ-${condOpts.size}-${targetOpts.size}"
            return AnswerRule(id, condQ, condMode, condOpts, targetQ, actMode, targetOpts, condRow, targetRow)
        }

        fun supportsQuestion(question: SurveyQuestionMeta): Boolean =
            question.entryType in SUPPORTED_ENTRY_TYPES

        fun sanitizeRules(
            rawRules: List<Map<String, Any?>>,
            questions: List<SurveyQuestionMeta> = emptyList(),
        ): List<Map<String, Any?>> {
            val questionByNum = questions.filter { it.num > 0 }.associateBy { it.num }
            return rawRules.mapNotNull { raw ->
                val rule = fromMap(raw) ?: return@mapNotNull null
                if (questionByNum.isNotEmpty()) {
                    val condition = questionByNum[rule.conditionQuestionNum] ?: return@mapNotNull null
                    val target = questionByNum[rule.targetQuestionNum] ?: return@mapNotNull null
                    if (!supportsQuestion(condition) || !supportsQuestion(target)) return@mapNotNull null
                }
                rule.toMap()
            }
        }
    }

    fun toMap(): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>(
            "id" to id,
            "condition_question_num" to conditionQuestionNum,
            "condition_mode" to conditionMode,
            "condition_option_indices" to conditionOptionIndices,
            "target_question_num" to targetQuestionNum,
            "action_mode" to actionMode,
            "target_option_indices" to targetOptionIndices,
        )
        conditionRowIndex?.let { out["condition_row_index"] = it }
        targetRowIndex?.let { out["target_row_index"] = it }
        return out
    }
}

/** 每份问卷已答题记录。复刻 persona/context.py 的 answered 追踪。 */
class AnsweredTracker {
    data class Record(
        val questionNum: Int,
        val questionType: String,
        val selectedIndices: List<Int> = emptyList(),
        val selectedTexts: List<String> = emptyList(),
        val textAnswer: String = "",
        val rowAnswers: MutableMap<Int, List<Int>> = mutableMapOf(),
    )

    private val answered = LinkedHashMap<Int, Record>()

    fun record(action: AnswerAction) {
        val questionNum = action.configQuestionNum
        if (questionNum <= 0) return
        when (action.kind) {
            "matrix" -> {
                val rec = answered.getOrPut(questionNum) { Record(questionNum, "matrix") }
                action.matrixIndices.forEachIndexed { row, col -> rec.rowAnswers[row] = listOf(col) }
            }
            "text" -> answered[questionNum] = Record(
                questionNum, "text", textAnswer = action.textValues.joinToString(" "),
            )
            else -> answered[questionNum] = Record(
                questionNum, action.recordType.ifEmpty { action.kind },
                selectedIndices = action.selectedIndices, selectedTexts = action.selectedTexts,
            )
        }
    }

    fun get(num: Int): Record? = answered[num]
    fun all(): Map<Int, Record> = answered
}

/**
 * 条件规则引擎：按已答记录裁剪后续题目的可选权重/集合。
 */
class ConsistencyEngine(
    rules: List<AnswerRule>,
    private val tracker: AnsweredTracker,
    private val onRuleMatched: ((AnswerRule) -> Unit)? = null,
) {
    private val rules = rules

    private fun sanitize(probs: List<Double>): List<Double> =
        probs.map { if (it.isNaN() || it < 0) 0.0 else it }

    private fun isTriggered(rule: AnswerRule): Boolean {
        if (rule.conditionQuestionNum >= rule.targetQuestionNum) return false
        val record = tracker.get(rule.conditionQuestionNum) ?: return false
        val selected = if (rule.conditionRowIndex != null) {
            (record.rowAnswers[rule.conditionRowIndex] ?: emptyList()).toSet()
        } else {
            record.selectedIndices.toSet()
        }
        val condSet = rule.conditionOptionIndices.toSet()
        if (condSet.isEmpty()) return false
        return when (rule.conditionMode) {
            "selected" -> selected.intersect(condSet).isNotEmpty()
            "not_selected" -> selected.intersect(condSet).isEmpty()
            else -> false
        }
    }

    private fun pickLatest(questionNum: Int, rowIndex: Int?): AnswerRule? {
        var selected: AnswerRule? = null
        for (rule in rules) {
            if (rule.targetQuestionNum != questionNum) continue
            if (rule.targetRowIndex != rowIndex) continue
            if (isTriggered(rule)) selected = rule  // 越靠后越优先
        }
        return selected
    }

    private fun applyRule(base: List<Double>, rule: AnswerRule): List<Double> {
        if (base.isEmpty()) return base
        val valid = rule.targetOptionIndices.filter { it in base.indices }.toSet()
        if (valid.isEmpty()) return base
        val adjusted = if (rule.actionMode == "must_select") {
            base.mapIndexed { i, w -> if (i in valid) w else 0.0 }
        } else {
            base.mapIndexed { i, w -> if (i in valid) 0.0 else w }
        }
        if (adjusted.sum() <= 0) return base
        onRuleMatched?.invoke(rule)
        return adjusted
    }

    fun applySingleLike(probs: List<Double>, questionNum: Int): List<Double> {
        val base = sanitize(probs)
        val rule = pickLatest(questionNum, null) ?: return base
        return applyRule(base, rule)
    }

    fun applyMatrixRow(probs: List<Double>, questionNum: Int, rowIndex: Int): List<Double> {
        val base = sanitize(probs)
        val rule = pickLatest(questionNum, rowIndex) ?: return base
        return applyRule(base, rule)
    }

    /** 多选约束：返回 (必选集合, 禁选集合)。 */
    fun multipleConstraint(questionNum: Int, optionCount: Int): Pair<Set<Int>, Set<Int>> {
        val rule = pickLatest(questionNum, null) ?: return emptySet<Int>() to emptySet()
        val valid = rule.targetOptionIndices.filter { it in 0 until optionCount }.toSet()
        if (valid.isEmpty()) return emptySet<Int>() to emptySet()
        onRuleMatched?.invoke(rule)
        return if (rule.actionMode == "must_select") valid to emptySet() else emptySet<Int>() to valid
    }
}
