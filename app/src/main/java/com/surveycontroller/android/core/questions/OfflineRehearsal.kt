package com.surveycontroller.android.core.questions

import com.surveycontroller.android.core.model.AnswerAction
import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.model.ExecutionState
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.persona.Persona
import com.surveycontroller.android.core.persona.PersonaBoostImpl
import com.surveycontroller.android.core.psychometrics.JointOptimizer
import com.surveycontroller.android.core.reverse_fill.ReverseFillSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.random.Random

internal object OfflineRehearsal {
    const val sampleLimit = 20
    const val defaultSeed = 42L
    private const val syntheticDataYear = 2026
    private const val aiPlaceholder = "〔AI 填空占位〕"

    data class QuestionResult(
        val number: Int,
        val title: String,
        val answer: String,
        val details: List<String>,
        val selections: List<String>,
        val answered: Boolean,
    )

    data class Sample(val number: Int, val sourceRow: Int?, val questions: List<QuestionResult>)

    data class Distribution(
        val number: Int,
        val title: String,
        val answeredCount: Int,
        val counts: Map<String, Int>,
    )

    data class Report(val seed: Long, val samples: List<Sample>, val distributions: List<Distribution>)

    class Issue(message: String, val questionNumber: Int? = null) : IllegalArgumentException(message)

    private val placeholderAi = object : AiTextProvider {
        override suspend fun generate(question: SurveyQuestionMeta, blankCount: Int, threadName: String): List<String> =
            List(blankCount) { aiPlaceholder }
    }

    suspend fun generate(config: ExecutionConfig, seed: Long, reverseFill: ReverseFillSpec? = null): Report {
        val questions = config.orderedQuestions().filter { it.num > 0 && !it.isDescription }
        if (questions.isEmpty()) throw Issue("请先解析问卷并配置答案")
        questions.firstOrNull { it.unsupported }?.let {
            throw Issue("第 ${it.num} 题暂不支持试跑", it.num)
        }
        HttpLogicPlanner.fallbackIssue(questions)?.let { issue ->
            throw Issue(issue.message, issue.questionNumber)
        }
        val replay = reverseFill?.samples?.take(sampleLimit)
        val count = replay?.size ?: sampleLimit
        if (count == 0) throw Issue("请先导入可用的反填数据")
        val configuration = config.copy(targetNum = count)
        val state = ExecutionState(configuration)
        val random = Random(seed)
        val jointPlan = if (configuration.reliabilityModeEnabled) JointOptimizer.build(configuration) else null

        val samples = (0 until count).map { index ->
            currentCoroutineContext().ensureActive()
            val sourceRow = replay?.get(index)
            val rules = mutableMapOf<Int, MutableList<String>>()
            val context = AnswerContext(
                config = configuration,
                state = state,
                tendency = TendencyState(random = random),
                reverseFill = sourceRow?.let { row ->
                    object : ReverseFillResolver {
                        override fun resolve(questionNum: Int, threadName: String): ReverseFillAnswer? = row.answers[questionNum]
                    }
                },
                aiText = placeholderAi,
                persona = PersonaBoostImpl(Persona.generate(random)),
                psychoPlan = jointPlan?.samplePlan(index),
                random = random,
                syntheticDataYear = syntheticDataYear,
                onRuleMatched = { rule ->
                    val row = rule.targetRowIndex?.let { " · 第 ${it + 1} 行" }.orEmpty()
                    val mode = if (rule.actionMode == "must_select") "必选" else "禁选"
                    rules.getOrPut(rule.targetQuestionNum) { mutableListOf() }
                        .add("命中第 ${rule.conditionQuestionNum} 题的${mode}规则$row")
                },
            )
            val builder = SurveyAnswerBuilder(context)
            val plan = builder.buildPlan { question ->
                currentCoroutineContext().ensureActive()
                try {
                    builder.buildAction(question) ?: throw Issue("第 ${question.num} 题无法生成答案，请检查题型和配置", question.num)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Issue) {
                    throw error
                } catch (error: Exception) {
                    throw Issue("第 ${question.num} 题无法试跑。${error.message.orEmpty()}", question.num)
                }
            }
            for (action in plan.actions) {
                for ((option, optionCount, row) in action.pendingDistributionChoices) {
                    val key = if (row == null) "q:${action.configQuestionNum}" else "matrix:${action.configQuestionNum}:$row"
                    state.appendPendingDistribution(context.threadName, key, option, optionCount)
                }
            }
            state.commitPendingDistribution(context.threadName)
            val skipped = plan.skippedQuestionNums.toSet()
            val answers = plan.actions.associateBy { it.questionNum }
            Sample(index + 1, sourceRow?.rowNumber, questions.map { question ->
                val action = answers[question.num]
                val configNum = action?.configQuestionNum ?: question.num
                questionResult(question, action, rules[configNum].orEmpty().distinct(), sourceRow?.answers?.containsKey(configNum) == true,
                    question.num in skipped)
            })
        }

        val distributions = questions.mapIndexed { index, question ->
            val results = samples.map { it.questions[index] }
            val type = config.configEntryFor(question)?.first
            val counts = initialCounts(question, type).toMutableMap()
            results.flatMap { it.selections }.forEach { label -> counts[label] = (counts[label] ?: 0) + 1 }
            Distribution(question.num, question.title, results.count { it.answered }, counts)
        }
        return Report(seed, samples, distributions)
    }

    private fun initialCounts(question: SurveyQuestionMeta, type: String?): Map<String, Int> {
        val labels = if (type == "matrix") question.optionTexts else runtimeOptionTexts(question)
        val optionCount = labels.size.takeIf { it > 0 } ?: question.options
        return when (type) {
            "matrix" -> buildMap {
                for (row in 0 until question.rows.coerceAtLeast(1)) {
                    for (option in 0 until optionCount.coerceAtLeast(2)) {
                        put(matrixLabel(question, row, option), 0)
                    }
                }
            }
            "single", "multiple", "dropdown", "score", "scale" ->
                (0 until optionCount.coerceAtLeast(1)).associate { optionLabel(question, it) to 0 }
            else -> emptyMap()
        }
    }

    private fun optionLabel(question: SurveyQuestionMeta, index: Int, matrix: Boolean = false): String {
        val labels = if (matrix) question.optionTexts else runtimeOptionTexts(question)
        val title = labels.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return "选项 ${index + 1}"
        return if (labels.indexOf(title) == labels.lastIndexOf(title)) title else "$title（选项 ${index + 1}）"
    }

    private fun matrixLabel(question: SurveyQuestionMeta, row: Int, option: Int): String {
        val rowLabel = "第 ${row + 1} 行"
        val title = question.rowTexts.getOrNull(row)?.takeIf { it.isNotBlank() }
        return listOfNotNull(rowLabel, title, optionLabel(question, option, matrix = true)).joinToString(" · ")
    }

    private fun questionResult(
        question: SurveyQuestionMeta,
        answer: AnswerAction?,
        rules: List<String>,
        replayed: Boolean,
        skipped: Boolean,
    ): QuestionResult {
        if (answer == null) {
            val status = if (skipped) "按问卷逻辑跳过" else "问卷提前结束"
            return QuestionResult(question.num, question.title, status, emptyList(), emptyList(), false)
        }
        val choices = answer.selectedIndices.map { optionLabel(question, it) }
        val matrix = answer.matrixIndices.mapIndexed { row, option -> matrixLabel(question, row, option) }
        val selections = if (answer.kind == "order") emptyList() else matrix.ifEmpty { choices }
        val values = when {
            matrix.isNotEmpty() -> matrix
            choices.isNotEmpty() -> answer.selectedIndices.map { index ->
                val additional = listOfNotNull(
                    answer.optionFillTexts.firstOrNull { it.first == index }?.second,
                    answer.attachedSelectChoices.firstOrNull { it.optionIndex == index }?.value,
                )
                (listOf(optionLabel(question, index)) + additional).joinToString(" · ")
            }
            answer.textValues.isNotEmpty() -> answer.textValues
            answer.sliderValue != null -> listOf(answer.sliderValue.toString())
            answer.scalarValue != null -> listOf(answer.scalarValue.toString())
            else -> answer.selectedTexts
        }
        val details = if (replayed) rules + "来自反填表格" else rules
        return QuestionResult(question.num, question.title,
            values.joinToString(if (answer.kind == "order") " → " else "；").ifEmpty { "空答案" },
            details, selections, true)
    }
}
