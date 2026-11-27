package com.surveycontroller.android.core.questions

import com.surveycontroller.android.core.model.AnswerAction
import com.surveycontroller.android.core.model.AttachedSelectChoice
import com.surveycontroller.android.core.model.AnswerPlan
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

internal const val OPTION_FILL_AI_TOKEN = "__AI_FILL__"

/**
 * 平台无关的答案生成。复刻各 provider 的 answering_builders 共有选择逻辑。
 * 产出 AnswerAction（含 questionId），各 Provider 再按自身格式编码提交。
 */
class SurveyAnswerBuilder(private val ctx: AnswerContext) {
    private companion object {
        const val FREE_AI_TEXT_PREFIX = "__FREE_AI_TEXT__"
        const val FREE_AI_OPTION_FILL_PREFIX = "__FREE_AI_OPTION_FILL__"
    }

    private data class Binding(
        val entryType: String,
        val configIndex: Int,
        val configQuestionNum: Int,
    )

    private data class MultipleSelectionBounds(
        val optionCount: Int,
        val minRequired: Int,
        val maxAllowed: Int,
        val requiredIndices: List<Int>,
        val blockedIndices: Set<Int>,
    )

    suspend fun buildPlan(): AnswerPlan {
        val questions = ctx.config.orderedQuestions()
        // 免费 AI 批量预填：把所有 AI 填空题一次性提交（批量优化），结果存入 state 供逐题读取
        prefillAiText(questions)
        // 跳题/显隐剪枝 + 逐题构建 + 记录已答（供条件规则引用）
        val plan = HttpLogicPlanner.build(questions, respectJumpLogic = true) { q ->
            val action = buildAction(q)
            if (action != null) ctx.answered.record(action)
            action
        }
        // v4.0.5+ AI 占位符防御：停止提交而非静默提交默认值
        assertNoFreeAiPlaceholders(plan.actions)
        return AnswerPlan(plan.actions, plan.skippedQuestionNums)
    }

    /**
     * v4.0.5+：复刻 software/core/ai/batch_runtime.py:assert_no_free_ai_placeholders_in_actions。
     * 遍历 actions，若任何 textValues / optionFillTexts / selectedTexts 命中
     * __FREE_AI_TEXT__ / __FREE_AI_OPTION_FILL__ 前缀，或 __AI_FILL__ 未被解析，则抛异常。
     */
    internal fun assertNoFreeAiPlaceholders(actions: List<AnswerAction>) {
        val questionNums = LinkedHashSet<Int>()
        for (action in actions) {
            val values = ArrayList<String>()
            values.addAll(action.textValues)
            values.addAll(action.optionFillTexts.map { it.second })
            values.addAll(action.selectedTexts)
            for (value in values) {
                val text = value.trim()
                if (text.startsWith(FREE_AI_TEXT_PREFIX) ||
                    text.startsWith(FREE_AI_OPTION_FILL_PREFIX) ||
                    text == OPTION_FILL_AI_TOKEN
                ) {
                    if (action.questionNum > 0) questionNums.add(action.questionNum)
                }
            }
        }
        if (questionNums.isNotEmpty()) {
            val labels = questionNums.sorted().take(8).joinToString("、") { "第${it}题" }
            throw AiTextException("问卷存在未替换的 AI 占位符，已停止提交：$labels")
        }
    }

    private suspend fun prefillAiText(questions: List<SurveyQuestionMeta>) {
        ctx.state.clearFreeAiPrefill(ctx.threadName)
        val provider = ctx.aiText ?: return
        val configQuestionByCurrentNum = HashMap<Int, Int>()
        val aiQuestions = questions.mapNotNull { q ->
            val binding = bind(q) ?: return@mapNotNull null
            val (entryType, configIndex) = binding
            if (entryType != "text" && entryType != "multi_text") return@mapNotNull null
            val fullAi = ctx.config.textAiFlags.getOrNull(configIndex) == true
            val hasBlankAi = ctx.config.multiTextBlankAiFlags.getOrNull(configIndex)?.any { it } == true
            if (!fullAi && !hasBlankAi) return@mapNotNull null
            configQuestionByCurrentNum[q.num] = binding.configQuestionNum
            q to maxOf(1, q.textInputs)
        }
        if (aiQuestions.isEmpty()) return
        val prefill = provider.prefill(aiQuestions, ctx.threadName)
        if (prefill.isEmpty()) return
        val normalized = prefill.entries.associate { entry ->
            (configQuestionByCurrentNum[entry.key] ?: entry.key) to entry.value
        }
        ctx.state.setFreeAiPrefill(ctx.threadName, normalized)
    }

    suspend fun buildAction(q: SurveyQuestionMeta): AnswerAction? {
        val binding = bind(q) ?: return null
        val (entryType, configIndex, configQuestionNum) = binding
        val action = when (entryType) {
            "single" -> buildSingle(q, binding)
            "multiple" -> buildMultiple(q, binding)
            "dropdown" -> buildDropdown(q, binding)
            "text", "multi_text" -> buildText(q, binding)
            "matrix" -> buildMatrix(q, binding)
            "scale" -> buildScoreLike(q, binding, "scale")
            "score" -> buildScoreLike(q, binding, "score")
            "slider" -> buildSlider(q, configIndex)
            "order" -> buildOrder(q)
            "location" -> buildLocation(q)
            else -> null
        }
        // 携带平台原生题目 id，供腾讯/credamo 编码使用
        return action?.copy(questionId = q.providerQuestionId, rootIndex = configQuestionNum)
    }

    private fun bind(q: SurveyQuestionMeta): Binding? {
        val entry = ctx.config.configEntryFor(q) ?: return null
        val configQuestionNum = ctx.config.configQuestionNumFor(q)
        return Binding(entry.first, entry.second, configQuestionNum)
    }

    private fun optionTexts(q: SurveyQuestionMeta): List<String> =
        q.optionTexts.map { it.trim() }.filter { it.isNotEmpty() }

    private fun validForced(index: Int?, optionCount: Int): Int? =
        index?.takeIf { it in 0 until optionCount }

    private suspend fun buildSingle(q: SurveyQuestionMeta, binding: Binding): AnswerAction {
        val configIndex = binding.configIndex
        val configQuestionNum = binding.configQuestionNum
        val texts = optionTexts(q)
        val optionCount = maxOf(1, texts.size.takeIf { it > 0 } ?: q.options)
        val reverse = ctx.reverseFill?.resolve(configQuestionNum, ctx.threadName)
        var forced = if (reverse?.kind == "choice") validForced(reverse.choiceIndex, optionCount) else null
        if (forced == null) forced = validForced(q.forcedOptionIndex, optionCount)

        val dimension = ctx.dimensionOf(configQuestionNum)
        val hasDim = ctx.hasReliabilityDimension(configQuestionNum)
        var strictRatio = false
        val selectedIndex: Int
        if (forced == null) {
            var probs = Probabilities.normalizeDroplistProbs(ctx.config.singleProb.getOrNull(configIndex) ?: -1, optionCount)
            strictRatio = ctx.isStrictRatio(configQuestionNum)
            if (!strictRatio) probs = ctx.persona.boost(texts, probs)
            if (!hasDim) probs = ctx.consistency.applySingleLike(probs, configQuestionNum)
            if (strictRatio || hasDim) {
                val reference = probs
                probs = applyDistribution(configQuestionNum, probs, optionCount, null)
                probs = Probabilities.enforceReferenceRankOrder(probs, reference)
            }
            selectedIndex = if (hasDim) ctx.tendency.getTendencyIndex(optionCount, probs, dimension, ctx.psychoPlan, configQuestionNum)
            else Probabilities.weightedIndex(probs)
        } else {
            selectedIndex = forced
        }
        val fill = resolveOptionFill(ctx.config.singleOptionFillTexts.getOrNull(configIndex), selectedIndex, q, configQuestionNum)
        val attached = resolveAttachedSelectChoice(ctx.config.singleAttachedOptionSelects.getOrNull(configIndex), selectedIndex)
        return AnswerAction(
            questionNum = q.num,
            kind = "choice",
            inputType = "radio",
            selectedIndices = listOf(selectedIndex),
            scalarValue = selectedIndex,
            optionFillTexts = if (fill != null) listOf(selectedIndex to fill) else emptyList(),
            attachedSelectChoices = listOfNotNull(attached),
            recordType = "single",
            pendingDistributionChoices = if (forced == null && (strictRatio || hasDim))
                listOf(Triple(selectedIndex, optionCount, null)) else emptyList(),
        )
    }

    private suspend fun buildDropdown(q: SurveyQuestionMeta, binding: Binding): AnswerAction {
        val configIndex = binding.configIndex
        val configQuestionNum = binding.configQuestionNum
        val texts = optionTexts(q)
        val optionCount = maxOf(1, texts.size.takeIf { it > 0 } ?: q.options)
        val reverse = ctx.reverseFill?.resolve(configQuestionNum, ctx.threadName)
        var forced = if (reverse?.kind == "choice") validForced(reverse.choiceIndex, optionCount) else null
        if (forced == null) forced = validForced(q.forcedOptionIndex, optionCount)

        val dimension = ctx.dimensionOf(configQuestionNum)
        val hasDim = ctx.hasReliabilityDimension(configQuestionNum)
        var strictRatio = false
        val selectedIndex: Int
        if (forced == null) {
            var probs = Probabilities.normalizeDroplistProbs(ctx.config.droplistProb.getOrNull(configIndex) ?: -1, optionCount)
            strictRatio = ctx.isStrictRatio(configQuestionNum)
            if (!strictRatio) probs = ctx.persona.boost(texts, probs)
            if (strictRatio || hasDim) {
                val reference = probs
                probs = applyDistribution(configQuestionNum, probs, optionCount, null)
                if (strictRatio) probs = Probabilities.enforceReferenceRankOrder(probs, reference)
            }
            selectedIndex = if (hasDim) ctx.tendency.getTendencyIndex(optionCount, probs, dimension, ctx.psychoPlan, configQuestionNum)
            else Probabilities.weightedIndex(probs)
        } else {
            selectedIndex = forced
        }
        val fill = resolveOptionFill(ctx.config.droplistOptionFillTexts.getOrNull(configIndex), selectedIndex, q, configQuestionNum)
        return AnswerAction(
            questionNum = q.num,
            kind = "select",
            selectedIndices = listOf(selectedIndex),
            scalarValue = selectedIndex,
            optionFillTexts = if (fill != null) listOf(selectedIndex to fill) else emptyList(),
            recordType = "dropdown",
            pendingDistributionChoices = if (forced == null && (strictRatio || hasDim))
                listOf(Triple(selectedIndex, optionCount, null)) else emptyList(),
        )
    }

    private fun buildScoreLike(q: SurveyQuestionMeta, binding: Binding, answerType: String): AnswerAction {
        val configIndex = binding.configIndex
        val configQuestionNum = binding.configQuestionNum
        val texts = optionTexts(q)
        val optionCount = maxOf(2, texts.size.takeIf { it > 0 } ?: q.options)
        val reverse = ctx.reverseFill?.resolve(configQuestionNum, ctx.threadName)
        var forced = if (reverse?.kind == "choice") validForced(reverse.choiceIndex, optionCount) else null
        if (forced == null) forced = validForced(q.forcedOptionIndex, optionCount)

        val selectedIndex: Int
        if (forced == null) {
            var probs = Probabilities.normalizeDroplistProbs(ctx.config.scaleProb.getOrNull(configIndex) ?: -1, optionCount)
            probs = ctx.consistency.applySingleLike(probs, configQuestionNum)
            probs = applyDistribution(configQuestionNum, probs, optionCount, null)
            selectedIndex = ctx.tendency.getTendencyIndex(optionCount, probs, ctx.dimensionOf(configQuestionNum), ctx.psychoPlan, configQuestionNum)
        } else {
            selectedIndex = forced
        }
        return AnswerAction(
            questionNum = q.num,
            kind = "choice",
            inputType = "radio",
            selectedIndices = listOf(selectedIndex),
            scalarValue = selectedIndex,
            recordType = answerType,
            pendingDistributionChoices = if (forced == null) listOf(Triple(selectedIndex, optionCount, null)) else emptyList(),
        )
    }

    private suspend fun buildMultiple(q: SurveyQuestionMeta, binding: Binding): AnswerAction? {
        val configIndex = binding.configIndex
        val configQuestionNum = binding.configQuestionNum
        val texts = optionTexts(q)
        val optionCount = maxOf(1, texts.size.takeIf { it > 0 } ?: q.options)
        var minRequired = maxOf(1, min(q.multiMinLimit ?: 1, optionCount))
        val maxAllowed = maxOf(1, min((q.multiMaxLimit ?: optionCount).takeIf { it > 0 } ?: optionCount, optionCount))
        if (minRequired > maxAllowed) minRequired = maxAllowed

        val (mustSelect, mustNotSelect) = ctx.consistency.multipleConstraint(configQuestionNum, optionCount)
        val bounds = MultipleSelectionBounds(
            optionCount = optionCount,
            minRequired = minRequired,
            maxAllowed = maxAllowed,
            requiredIndices = mustSelect.filter { it in 0 until optionCount }.distinct().sorted(),
            blockedIndices = mustNotSelect.filter { it in 0 until optionCount }.toSet(),
        )

        val reverse = ctx.reverseFill?.resolve(configQuestionNum, ctx.threadName)
        if (reverse?.kind == "choice" && (reverse.choiceIndexes.isNotEmpty() || reverse.choiceIndex != null)) {
            val fallback = shuffledAvailableIndices(bounds)
            val replayed = reverse.choiceIndexes.ifEmpty { listOfNotNull(reverse.choiceIndex) }
            return finalizeMultiple(
                q,
                configIndex,
                normalizeMultipleSelection(replayed, bounds, fallback),
            )
        }

        val raw = ctx.config.multipleProb.getOrNull(configIndex)
        val randomCount = raw == null || (raw.size == 1 && raw[0] == -1.0)
        if (randomCount) {
            val pool = (0 until optionCount)
                .filter { it !in bounds.blockedIndices && it !in bounds.requiredIndices }
                .toMutableList()
            val requiredCount = minOf(bounds.requiredIndices.size, bounds.maxAllowed)
            val minTotal = maxOf(bounds.minRequired, requiredCount)
            val maxTotal = minOf(bounds.maxAllowed, requiredCount + pool.size)
            val extraMin = maxOf(0, minTotal - requiredCount)
            val extraMax = maxOf(0, maxTotal - requiredCount)
            val extra = if (extraMax >= extraMin && extraMax > 0) Random.nextInt(extraMin, extraMax + 1) else extraMin.coerceAtMost(extraMax)
            pool.shuffle()
            return finalizeMultiple(
                q,
                configIndex,
                normalizeMultipleSelection(pool.take(extra), bounds, pool.drop(extra)),
            )
        }

        var probs = MutableList(optionCount) { i ->
            val v = raw?.getOrNull(i) ?: 0.0
            if (v.isNaN() || v.isInfinite()) 0.0 else v.coerceIn(0.0, 100.0)
        }
        val strictRatio = ctx.isStrictRatio(configQuestionNum)
        if (!strictRatio) probs = ctx.persona.boost(texts, probs).map { min(100.0, it) }.toMutableList()
        for (idx in bounds.blockedIndices) if (idx in probs.indices) probs[idx] = 0.0
        for (idx in bounds.requiredIndices) if (idx in probs.indices) probs[idx] = 0.0

        if (strictRatio) {
            val positiveOptional = (0 until optionCount)
                .filter { probs[it] > 0 && it !in bounds.blockedIndices && it !in bounds.requiredIndices }
            val requiredCount = minOf(bounds.requiredIndices.size, bounds.maxAllowed)
            val minTotal = maxOf(bounds.minRequired, requiredCount)
            val maxTotal = minOf(bounds.maxAllowed, requiredCount + positiveOptional.size)
            val expectedOptional = positiveOptional.sumOf { probs[it] } / 100.0
            val totalTarget = (requiredCount + Probabilities.stochasticRound(expectedOptional))
                .coerceIn(minTotal, maxOf(minTotal, maxTotal))
            val optionalTarget = maxOf(0, totalTarget - requiredCount)
            val sampled = Probabilities.weightedSampleWithoutReplacement(positiveOptional, positiveOptional.map { probs[it] }, optionalTarget)
            return finalizeMultiple(
                q,
                configIndex,
                normalizeMultipleSelection(sampled, bounds, positiveOptional.shuffled()),
            )
        }

        val positive = (0 until optionCount).filter { probs[it] > 0 }
        // v4.0.3 credamo：所有选项概率为 0、无必选项且无显式"至少选N项"时跳过该题
        // （对齐 credamo v4.0.6 _normalize_positive_indices 移除随机兜底的行为）
        if (positive.isEmpty()
            && bounds.requiredIndices.isEmpty()
            && q.multiMinLimit == null
        ) {
            return null
        }
        var mask: List<Int> = emptyList()
        var attempts = 0
        if (positive.isNotEmpty()) {
            while ((mask.isEmpty() || mask.sum() == 0) && attempts < 32) {
                mask = probs.map { if (Random.nextDouble() < it / 100.0) 1 else 0 }
                attempts++
            }
            if (mask.sum() == 0) {
                val m = MutableList(optionCount) { 0 }
                m[positive.random()] = 1
                mask = m
            }
        } else {
            mask = MutableList(optionCount) { 0 }
        }
        var selected = (0 until optionCount).filter { mask[it] == 1 && probs[it] > 0 }.toMutableList()
        return finalizeMultiple(q, configIndex, normalizeMultipleSelection(selected, bounds, positive.shuffled()))
    }

    private suspend fun finalizeMultiple(q: SurveyQuestionMeta, configIndex: Int, selectedIndices: List<Int>): AnswerAction? {
        val optionCount = maxOf(1, optionTexts(q).size.takeIf { it > 0 } ?: q.options)
        val selected = normalizeIndices(selectedIndices, optionCount)
        if (selected.isEmpty()) return null
        val fillEntries = ctx.config.multipleOptionFillTexts.getOrNull(configIndex)
        val fills = mutableListOf<Pair<Int, String>>()
        val configQuestionNum = ctx.config.configQuestionNumFor(q)
        for (idx in selected) resolveOptionFill(fillEntries, idx, q, configQuestionNum)?.let { fills.add(idx to it) }
        val attachedConfigs = ctx.config.multipleAttachedOptionSelects.getOrNull(configIndex)
        val attached = selected.mapNotNull { idx -> resolveAttachedSelectChoice(attachedConfigs, idx) }
        return AnswerAction(
            questionNum = q.num,
            kind = "choice",
            inputType = "checkbox",
            selectedIndices = selected,
            optionFillTexts = fills,
            attachedSelectChoices = attached,
            recordType = "multiple",
        )
    }

    private fun buildMatrix(q: SurveyQuestionMeta, binding: Binding): AnswerAction {
        val configIndex = binding.configIndex
        val configQuestionNum = binding.configQuestionNum
        val rowCount = maxOf(1, q.rows)
        val optionCount = maxOf(2, q.optionTexts.size.takeIf { it > 0 } ?: q.options)
        val reverse = ctx.reverseFill?.resolve(configQuestionNum, ctx.threadName)
        val forcedIndices = if (reverse?.kind == "matrix")
            reverse.matrixChoiceIndexes.filter { it >= 0 } else emptyList()
        val strictRatio = ctx.isStrictRatio(configQuestionNum)
        val selected = ArrayList<Int>(rowCount)
        val pending = mutableListOf<Triple<Int, Int, Int?>>()
        var nextIndex = configIndex
        for (row in 0 until rowCount) {
            val selectedIndex: Int
            if (row < forcedIndices.size) {
                selectedIndex = forcedIndices[row].coerceIn(0, optionCount - 1)
            } else {
                val raw = ctx.config.matrixProb.getOrNull(nextIndex)
                var rowProbs: List<Double>
                var reference: List<Double>? = null
                if (raw is List<*>) {
                    var p = raw.map { (it as? Number)?.toDouble() ?: 0.0 }
                    if (p.size != optionCount) p = List(optionCount) { 1.0 }
                    p = ctx.consistency.applyMatrixRow(p, configQuestionNum, row)
                    reference = p
                    rowProbs = if (p.any { it > 0 }) applyDistribution(configQuestionNum, p, optionCount, row) else p
                } else {
                    val uniform = ctx.consistency.applyMatrixRow(List(optionCount) { 1.0 }, configQuestionNum, row)
                    rowProbs = if (uniform.any { it > 0 }) applyDistribution(configQuestionNum, uniform, optionCount, row) else uniform
                }
                if (strictRatio) rowProbs = Probabilities.enforceReferenceRankOrder(rowProbs, reference ?: rowProbs)
                selectedIndex = ctx.tendency.getTendencyIndex(optionCount, rowProbs, ctx.dimensionOf(configQuestionNum), ctx.psychoPlan, configQuestionNum, row)
                pending.add(Triple(selectedIndex, optionCount, row))
            }
            selected.add(selectedIndex)
            nextIndex++
        }
        return AnswerAction(
            questionNum = q.num,
            kind = "matrix",
            matrixIndices = selected,
            recordType = "matrix",
            pendingDistributionChoices = pending,
        )
    }

    private suspend fun buildText(q: SurveyQuestionMeta, binding: Binding): AnswerAction {
        val configIndex = binding.configIndex
        val configQuestionNum = binding.configQuestionNum
        val blankCount = maxOf(1, q.textInputs)
        val reverse = ctx.reverseFill?.resolve(configQuestionNum, ctx.threadName)
        val entryType = ctx.config.textEntryTypes.getOrNull(configIndex) ?: "text"
        val blankAiFlags = ctx.config.multiTextBlankAiFlags.getOrNull(configIndex) ?: emptyList()
        val values: List<String> = when {
            reverse?.kind == "multi_text" ->
                reverse.textValues.map { it.trim().ifEmpty { RandomText.DEFAULT_FILL_TEXT } }
            reverse?.kind == "text" ->
                listOf((reverse.textValue ?: "").trim().ifEmpty { RandomText.DEFAULT_FILL_TEXT })
            ctx.config.textAiFlags.getOrNull(configIndex) == true -> resolveAiText(q, blankCount)
            else -> {
                val base = TextValues.resolve(
                    candidates = ctx.config.texts.getOrNull(configIndex) ?: listOf(RandomText.DEFAULT_FILL_TEXT),
                    probs = ctx.config.textsProb.getOrNull(configIndex) ?: listOf(1.0),
                    blankCount = blankCount,
                    entryType = entryType,
                    blankModes = ctx.config.multiTextBlankModes.getOrNull(configIndex) ?: emptyList(),
                    blankIntRanges = ctx.config.multiTextBlankIntRanges.getOrNull(configIndex) ?: emptyList(),
                    gender = ctx.persona.gender(),
                )
                if (entryType == "multi_text" && blankAiFlags.any { it }) {
                    val aiValues = resolveAiText(q, blankCount)
                    (0 until blankCount).map { idx ->
                        if (blankAiFlags.getOrNull(idx) == true) {
                            (aiValues.getOrNull(idx) ?: aiValues.lastOrNull() ?: RandomText.DEFAULT_FILL_TEXT)
                        } else {
                            (base.getOrNull(idx) ?: base.lastOrNull() ?: RandomText.DEFAULT_FILL_TEXT)
                        }
                    }
                } else {
                    base
                }
            }
        }
        val normalized = if (values.isEmpty()) listOf(RandomText.DEFAULT_FILL_TEXT) else values
        val padded = (0 until blankCount).map {
            (normalized.getOrNull(it) ?: normalized.last()).trim().ifEmpty { RandomText.DEFAULT_FILL_TEXT }
        }
        return AnswerAction(questionNum = q.num, kind = "text", textValues = padded, recordType = "text")
    }

    private suspend fun resolveAiText(q: SurveyQuestionMeta, blankCount: Int): List<String> {
        // 优先用批量预填结果
        val configQuestionNum = ctx.config.configQuestionNumFor(q)
        ctx.state.getFreeAiPrefill(ctx.threadName, configQuestionNum)?.let { cached ->
            if (cached.isNotEmpty()) {
                return (0 until blankCount).map { (cached.getOrNull(it) ?: cached.last()) }
            }
        }
        val provider = ctx.aiText ?: throw AiTextException("AI 填空未配置，已停止提交")
        val generated = provider.generate(q, blankCount, ctx.threadName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (generated.isEmpty()) throw AiTextException("AI 未返回可用填空内容")
        return generated
    }

    private fun buildSlider(q: SurveyQuestionMeta, configIndex: Int): AnswerAction {
        val configQuestionNum = ctx.config.configQuestionNumFor(q)
        val reverse = ctx.reverseFill?.resolve(configQuestionNum, ctx.threadName)
        val replayed = reverse?.takeIf { it.kind == "slider" }?.sliderValue
        val configured = ctx.config.sliderTargets.getOrNull(configIndex)
        val target = when {
            replayed != null && !replayed.isNaN() && !replayed.isInfinite() -> clampSliderValue(replayed, q)
            configured == null || configured.isNaN() || configured.isInfinite() -> randomSliderValue(q)
            else -> clampSliderValue(configured, q)
        }
        return AnswerAction(questionNum = q.num, kind = "slider", sliderValue = target, recordType = "slider")
    }

    private data class SliderBounds(val min: Double, val max: Double)

    private fun randomSliderValue(q: SurveyQuestionMeta): Double {
        val bounds = sliderBounds(q)
        if (bounds.max <= bounds.min) return bounds.min
        val step = finiteDouble(q.sliderStep)?.takeIf { it > 0.0 }
        if (step != null) {
            val steps = floor((bounds.max - bounds.min) / step).toInt().coerceAtLeast(0)
            val selectedStep = if (steps > 0) Random.nextInt(0, steps + 1) else 0
            return (bounds.min + selectedStep * step).coerceIn(bounds.min, bounds.max)
        }
        return Random.nextDouble(bounds.min, bounds.max)
    }

    private fun clampSliderValue(value: Double, q: SurveyQuestionMeta): Double {
        val bounds = sliderBounds(q)
        return value.coerceIn(bounds.min, bounds.max)
    }

    private fun sliderBounds(q: SurveyQuestionMeta): SliderBounds {
        val lo = finiteDouble(q.sliderMin) ?: 0.0
        val rawHi = finiteDouble(q.sliderMax)
        val hi = if (rawHi != null && rawHi >= lo) rawHi else 100.0
        return if (hi >= lo) SliderBounds(lo, hi) else SliderBounds(50.0, 50.0)
    }

    private fun finiteDouble(value: Double?): Double? =
        value?.takeUnless { it.isNaN() || it.isInfinite() }

    private fun buildLocation(q: SurveyQuestionMeta): AnswerAction {
        val configQuestionNum = ctx.config.configQuestionNumFor(q)
        val reverse = ctx.reverseFill?.resolve(configQuestionNum, ctx.threadName)
        val configured = reverse
            ?.takeIf { it.kind == "location" }
            ?.locationParts
            .completeLocationParts()
            ?: ctx.config.locationParts[configQuestionNum].completeLocationParts()
            ?: listOf("北京", "北京", "东城区")
        val parts = configured.take(3)
        return AnswerAction(questionNum = q.num, kind = "text", textValues = parts, recordType = "location")
    }

    private fun List<String>?.completeLocationParts(): List<String>? {
        val parts = orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
        return parts.takeIf { it.size >= 3 }
    }

    private fun buildOrder(q: SurveyQuestionMeta): AnswerAction {
        val optionCount = maxOf(1, optionTexts(q).size.takeIf { it > 0 } ?: q.options)
        val order = (0 until optionCount).toMutableList().also { it.shuffle() }
        return AnswerAction(questionNum = q.num, kind = "order", selectedIndices = order, recordType = "order")
    }

    private fun applyDistribution(questionNum: Int, probs: List<Double>, optionCount: Int, rowIndex: Int?): List<Double> {
        val statKey = if (rowIndex == null) "q:$questionNum" else "matrix:$questionNum:$rowIndex"
        val (total, counts) = ctx.state.snapshotDistribution(statKey, optionCount)
        if (total <= 0) return probs
        return DistributionCorrection.resolve(
            target = probs, optionCount = optionCount, total = total, counts = counts,
            usePriorityProfile = ctx.hasReliabilityDimension(questionNum),
        )
    }

    private suspend fun resolveOptionFill(
        entries: List<String?>?,
        optionIndex: Int,
        question: SurveyQuestionMeta,
        configQuestionNum: Int,
    ): String? {
        val candidates = ctx.config.optionFillCandidates[configQuestionNum]?.get(optionIndex)
        val raw = if (candidates != null) candidates.randomOrNull() else entries?.getOrNull(optionIndex)
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return defaultMissingOptionFill(question, optionIndex, null)
        if (text == OPTION_FILL_AI_TOKEN) return resolveAiOptionFill(question, configQuestionNum, optionIndex)
        return defaultMissingOptionFill(
            question,
            optionIndex,
            RandomText.resolveDynamicToken(text, ctx.persona.gender()),
        )
    }

    /**
     * v4.0.3：选项填空项为空 + 含填空选项单选无法作答的兜底。
     * 复刻 software/providers/answering/option_fill.py:default_missing_option_fill。
     * 当填空值非空 → 透传；否则若该选项在 fillableOptions 中 → 返回 DEFAULT_FILL_TEXT；
     * 否则返回 null（不附加填空）。
     */
    private fun defaultMissingOptionFill(
        question: SurveyQuestionMeta,
        optionIndex: Int,
        fill: String?,
    ): String? {
        val trimmed = fill?.trim().orEmpty()
        if (trimmed.isNotEmpty()) return trimmed
        if (optionIndex in question.fillableOptions) return RandomText.DEFAULT_FILL_TEXT
        return null
    }

    private suspend fun resolveAiOptionFill(
        question: SurveyQuestionMeta,
        configQuestionNum: Int,
        optionIndex: Int,
    ): String {
        val provider = ctx.aiText ?: return RandomText.DEFAULT_FILL_TEXT
        val optionText = optionTexts(question).getOrNull(optionIndex).orEmpty()
        val promptQuestion = question.copy(
            num = configQuestionNum,
            title = buildString {
                append(question.title.ifBlank { "第${configQuestionNum}题" })
                append("\n\n当前需要填写的是某个选择题选项后面的补充输入框。")
                if (optionText.isNotBlank()) append("\n已选择的选项是：").append(optionText)
                append("\n请只输出最终要填写的内容，不要解释。")
            },
        )
        val generated = provider.generate(promptQuestion, 1, ctx.threadName)
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return generated ?: throw AiTextException("AI 未返回选项填空内容")
    }

    private fun resolveAttachedSelectChoice(
        configs: List<Map<String, Any?>>?,
        optionIndex: Int,
    ): AttachedSelectChoice? {
        val config = configs
            ?.firstOrNull { it["option_index"].asIntOrNull() == optionIndex }
            ?: return null
        val options = config["select_options"].asStringList()
        if (options.isEmpty()) return null
        val weights = config["weights"].asDoubleList()
            .takeIf { it.size == options.size && it.any { value -> value > 0.0 } }
            ?: List(options.size) { 1.0 }
        val selected = Probabilities.weightedIndex(weights).coerceIn(0, options.lastIndex)
        val values = config["select_values"].asStringList()
        val value = values.getOrNull(selected)?.trim()?.takeIf { it.isNotEmpty() }
            ?: options.getOrNull(selected).orEmpty()
        return AttachedSelectChoice(optionIndex = optionIndex, selectedIndex = selected, value = value)
    }

    private fun normalizeIndices(indices: List<Int>, optionCount: Int): List<Int> =
        indices.filter { it in 0 until optionCount }.distinct().sorted()

    private fun normalizeMultipleSelection(
        candidates: List<Int>,
        bounds: MultipleSelectionBounds,
        preferredFill: List<Int> = emptyList(),
    ): List<Int> {
        val selected = LinkedHashSet<Int>()

        fun add(index: Int): Boolean {
            if (selected.size >= bounds.maxAllowed) return false
            if (index !in 0 until bounds.optionCount || index in bounds.blockedIndices) return false
            selected.add(index)
            return true
        }

        bounds.requiredIndices.forEach { add(it) }
        candidates.forEach { add(it) }

        val effectiveMin = minOf(bounds.maxAllowed, maxOf(bounds.minRequired, selected.size))
        preferredFill.forEach {
            if (selected.size >= effectiveMin) return@forEach
            add(it)
        }
        if (selected.size < effectiveMin) {
            shuffledAvailableIndices(bounds).forEach {
                if (selected.size >= effectiveMin) return@forEach
                add(it)
            }
        }
        return normalizeIndices(selected.toList(), bounds.optionCount)
    }

    private fun shuffledAvailableIndices(bounds: MultipleSelectionBounds): List<Int> =
        (0 until bounds.optionCount).filter { it !in bounds.blockedIndices }.shuffled()

    private fun Any?.asIntOrNull(): Int? = when (this) {
        is Number -> toInt()
        else -> this?.toString()?.trim()?.toIntOrNull()
    }

    private fun Any?.asStringList(): List<String> = when (this) {
        is List<*> -> map { it?.toString()?.trim().orEmpty() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun Any?.asDoubleList(): List<Double> = when (this) {
        is List<*> -> mapNotNull { item ->
            when (item) {
                is Number -> item.toDouble()
                else -> item?.toString()?.trim()?.toDoubleOrNull()
            }
        }
        else -> emptyList()
    }
}
