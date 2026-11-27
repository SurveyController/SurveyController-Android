package com.surveycontroller.android.core.questions

import com.surveycontroller.android.core.model.AnswerAction
import com.surveycontroller.android.core.model.SurveyQuestionMeta

/**
 * HTTP 提交链路的问卷逻辑执行器。1:1 复刻 software/providers/http_logic.py。
 * 负责按跳题/显隐条件剪枝题目，决定实际作答与跳过的题目。
 */
object HttpLogicPlanner {

    data class Plan(
        val actions: List<AnswerAction>,
        val skippedQuestionNums: List<Int>,
        val terminatedEarly: Boolean = false,
    )

    private val TERMINATE_KEYWORDS = listOf("结束作答", "结束答题", "结束填写", "终止作答", "停止作答")
    private val SUPPORTED_CONDITION_MODES = setOf("selected", "not_selected")

    private fun ordered(questions: List<SurveyQuestionMeta>): List<SurveyQuestionMeta> =
        questions.filter { it.num > 0 }.sortedWith(compareBy({ it.page }, { it.num }))

    private fun questionHasLogic(q: SurveyQuestionMeta): Boolean =
        q.hasJump || q.hasDisplayCondition || q.hasDependentDisplayLogic

    private fun ruleTerminates(rule: com.surveycontroller.android.core.model.JumpRule): Boolean {
        if (rule.terminates) return true
        val text = rule.optionText?.trim().orEmpty()
        return text.isNotEmpty() && TERMINATE_KEYWORDS.any { it in text }
    }

    private fun logicStatusCompleteEnough(q: SurveyQuestionMeta): Boolean {
        val status = q.logicParseStatus.trim().lowercase()
        if (status == "complete") return true
        if (status != "unknown") return false
        if (q.hasJump && q.jumpRules.isEmpty()) return false
        if (q.hasDisplayCondition && q.displayConditions.isEmpty()) return false
        if (q.hasDependentDisplayLogic && q.controlsDisplayTargets.isEmpty()) return false
        return true
    }

    /** 返回非空字符串表示该问卷逻辑无法被纯 HTTP 安全执行（原版会回退浏览器，移动端直接报错）。 */
    fun fallbackReason(questions: List<SurveyQuestionMeta>): String = fallbackIssue(questions)?.message.orEmpty()

    internal data class LogicIssue(val questionNumber: Int, val message: String)

    internal fun fallbackIssue(questions: List<SurveyQuestionMeta>): LogicIssue? {
        val ordered = ordered(questions)
        val maxNum = ordered.maxOfOrNull { it.num } ?: 0
        for (q in ordered) {
            if (q.num <= 0 || !questionHasLogic(q)) continue
            if (!logicStatusCompleteEnough(q)) return LogicIssue(q.num, "第${q.num}题逻辑规则未完整解析")
            for (cond in q.displayConditions) {
                val src = cond.conditionQuestionNum
                val mode = cond.conditionMode.trim().ifEmpty { "selected" }
                if (src <= 0) return LogicIssue(q.num, "第${q.num}题显隐条件缺少来源题号")
                if (src >= q.num) return LogicIssue(q.num, "第${q.num}题显隐条件依赖未来题目")
                if (mode !in SUPPORTED_CONDITION_MODES) return LogicIssue(q.num, "第${q.num}题显隐条件模式不支持：$mode")
            }
            for (target in q.controlsDisplayTargets) {
                val mode = target.conditionMode.trim().ifEmpty { "selected" }
                if (target.targetQuestionNum <= q.num) return LogicIssue(q.num, "第${q.num}题控制显示规则存在回跳")
                if (mode !in SUPPORTED_CONDITION_MODES) return LogicIssue(q.num, "第${q.num}题控制显示模式不支持：$mode")
            }
            for (rule in q.jumpRules) {
                if (ruleTerminates(rule)) continue
                val target = rule.targetQuestion ?: 0
                if (target <= q.num) return LogicIssue(q.num, "第${q.num}题跳题目标回跳到已过题目")
                if (maxNum > 0 && target > maxNum + 1) return LogicIssue(q.num, "第${q.num}题跳题目标超出问卷范围")
            }
        }
        return null
    }

    private fun actionSelectedIndices(action: AnswerAction): Set<Int> =
        if (action.kind == "matrix") action.matrixIndices.filter { it >= 0 }.toSet()
        else action.selectedIndices.filter { it >= 0 }.toSet()

    private fun conditionMet(actionByNum: Map<Int, AnswerAction>, cond: com.surveycontroller.android.core.model.DisplayCondition): Boolean {
        val src = cond.conditionQuestionNum
        if (src <= 0) return false
        val sourceAction = actionByNum[src] ?: return false
        val mode = cond.conditionMode.trim().ifEmpty { "selected" }
        val normalized = cond.conditionOptionIndices.filter { it >= 0 }.toSet()
        val selected = actionSelectedIndices(sourceAction)
        if (normalized.isEmpty()) return mode == "selected"
        return when (mode) {
            "selected" -> selected.intersect(normalized).isNotEmpty()
            "not_selected" -> selected.intersect(normalized).isEmpty()
            else -> false
        }
    }

    private fun questionVisible(q: SurveyQuestionMeta, actionByNum: Map<Int, AnswerAction>): Boolean {
        val conditions = q.displayConditions
        if (conditions.isEmpty()) return !q.hasDisplayCondition
        // 按 (来源题, 模式) 分组：组内任一命中即满足，所有组都需满足
        val grouped = conditions.groupBy { it.conditionQuestionNum to it.conditionMode.trim().ifEmpty { "selected" } }
            .filterKeys { it.first > 0 }
        if (grouped.isEmpty()) return !q.hasDisplayCondition
        return grouped.values.all { group -> group.any { conditionMet(actionByNum, it) } }
    }

    private fun resolveJumpTarget(q: SurveyQuestionMeta, action: AnswerAction): Pair<Int?, Boolean> {
        val selected = actionSelectedIndices(action)
        var unconditionalTarget: Int? = null
        var unconditionalTerminates = false
        for (rule in q.jumpRules) {
            val target = rule.targetQuestion ?: 0
            if (target <= 0) continue
            val terminates = ruleTerminates(rule)
            if (rule.optionIndex < 0) {
                if (unconditionalTarget == null) { unconditionalTarget = target; unconditionalTerminates = terminates }
                continue
            }
            if (rule.optionIndex in selected) return target to terminates
        }
        return unconditionalTarget to unconditionalTerminates
    }

    /** 构建逻辑计划。build 每题调用 buildAction；返回 null 视为不支持纯 HTTP。 */
    suspend fun build(
        questions: List<SurveyQuestionMeta>,
        respectJumpLogic: Boolean = true,
        buildAction: suspend (SurveyQuestionMeta) -> AnswerAction?,
    ): Plan {
        val ordered = ordered(questions)
        val reason = fallbackReason(ordered)
        if (reason.isNotEmpty()) error("$reason，暂不支持纯 HTTP 提交")

        val maxNum = ordered.maxOfOrNull { it.num } ?: 0
        val actionByNum = LinkedHashMap<Int, AnswerAction>()
        val actions = mutableListOf<AnswerAction>()
        val skipped = mutableListOf<Int>()
        var jumpTargetNum: Int? = null

        for (q in ordered) {
            val num = q.num
            if (num <= 0) continue
            if (jumpTargetNum != null) {
                if (num < jumpTargetNum) { skipped.add(num); continue }
                jumpTargetNum = null
            }
            if (!questionVisible(q, actionByNum)) { skipped.add(num); continue }

            val action = buildAction(q)
            if (action == null) error("第${num}题暂不支持纯 HTTP 提交")
            actionByNum[num] = action
            actions.add(action)

            if (respectJumpLogic) {
                val (target, terminates) = resolveJumpTarget(q, action)
                if (target == null) continue
                if (terminates || target > maxNum) {
                    return Plan(actions, skipped, terminatedEarly = true)
                }
                jumpTargetNum = target
            }
        }
        return Plan(actions, skipped, false)
    }
}
