package com.surveycontroller.android.core.questions

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/** 心理测量计划查询接口（由 JointOptimizer.SamplePlan 实现）。 */
interface PsychoPlanLookup {
    fun getChoice(questionIndex: Int, rowIndex: Int?): Int?
    fun isLocked(questionIndex: Int, rowIndex: Int?): Boolean
}

/** 按线程提供当前份样本的心理测量计划。 */
interface SamplePlanProvider {
    fun planFor(threadName: String): PsychoPlanLookup?
}

/**
 * 答题倾向：保证同一份问卷内同维度量表题的前后一致性。
 * 复刻 software/core/questions/tendency.py。每份问卷创建一个独立 TendencyState（对应 reset_tendency）。
 */
class TendencyState(
    private val profile: ReliabilityProfile = DEFAULT_RELIABILITY_PROFILE,
    private val random: Random = Random.Default,
) {
    private val dimensionBases = HashMap<String, Double>()
    private val smallScaleStaticMaxOptions = 3

    private fun isUngrouped(dimension: String?): Boolean =
        dimension == null || dimension == DIMENSION_UNGROUPED

    private fun randomByProbabilities(optionCount: Int, probabilities: List<Double>?): Int {
        return if (probabilities != null && probabilities.size == optionCount) {
            Probabilities.weightedIndex(probabilities, random)
        } else {
            random.nextInt(optionCount)
        }
    }

    private fun generateBaseRatio(optionCount: Int, probabilities: List<Double>?): Double {
        if (probabilities == null) return random.nextDouble()
        if (probabilities.isNotEmpty()) {
            val idx = Probabilities.weightedIndex(probabilities, random)
            return idx.toDouble() / maxOf(optionCount - 1, 1)
        }
        return random.nextDouble()
    }

    private fun resolveFluctuationWindow(optionCount: Int): Int {
        if (optionCount <= smallScaleStaticMaxOptions) return 0
        val span = maxOf(optionCount, 1)
        var window = (span * profile.consistencyWindowRatio).roundToInt()
        if (window < 1) window = 1
        return minOf(window, profile.consistencyWindowMax)
    }

    private fun windowDecay(distance: Int, window: Int): Double {
        if (distance <= 0) return profile.consistencyCenterWeight
        if (window <= 0) return 0.0
        val normalized = minOf(1.0, distance.toDouble() / window.toDouble())
        val center = profile.consistencyCenterWeight
        val edge = minOf(center, profile.consistencyEdgeWeight)
        return maxOf(edge, center - (center - edge) * normalized)
    }

    private fun normalizeForZeroGuard(optionCount: Int, probabilities: List<Double>?): List<Double>? {
        if (optionCount <= 0 || probabilities == null) return null
        return List(optionCount) { i ->
            val raw = probabilities.getOrNull(i) ?: 0.0
            if (raw.isNaN() || raw.isInfinite() || raw <= 0.0) 0.0 else raw
        }
    }

    /** 硬约束：权重为 0 的选项绝不被选中。 */
    private fun enforceZeroWeightGuard(
        selectedIndex: Int,
        optionCount: Int,
        probabilities: List<Double>?,
        anchorIndex: Int?,
    ): Int {
        if (optionCount <= 0) return 0
        val selected = selectedIndex.coerceIn(0, optionCount - 1)
        val normalized = normalizeForZeroGuard(optionCount, probabilities) ?: return selected
        val positive = normalized.indices.filter { normalized[it] > 0.0 }
        if (positive.isEmpty()) {
            error("当前题目所有选项权重均为 0，无法作答，请至少保留一个非 0 选项。")
        }
        if (selected in positive) return selected
        val target = anchorIndex?.coerceIn(0, optionCount - 1) ?: selected
        var best = positive[0]
        var bestDistance = abs(best - target)
        var bestWeight = normalized[best]
        for (idx in positive.drop(1)) {
            val distance = abs(idx - target)
            val weight = normalized[idx]
            if (distance < bestDistance ||
                (distance == bestDistance && weight > bestWeight) ||
                (distance == bestDistance && weight == bestWeight && idx < best)
            ) {
                best = idx; bestDistance = distance; bestWeight = weight
            }
        }
        return best
    }

    private fun applyConsistency(base: Int, optionCount: Int, probabilities: List<Double>?): Int {
        val effectiveBase = minOf(base, optionCount - 1)
        val window = resolveFluctuationWindow(optionCount)
        if (window <= 0) return effectiveBase
        val low = maxOf(0, effectiveBase - window)
        val high = minOf(optionCount - 1, effectiveBase + window)
        if (probabilities != null && probabilities.size == optionCount) {
            val adjusted = List(optionCount) { i ->
                if (i in low..high) probabilities[i] * windowDecay(abs(i - effectiveBase), window)
                else probabilities[i] * profile.consistencyOutsideDecay
            }
            if (adjusted.sum() > 0) return Probabilities.weightedIndex(adjusted)
        }
        val candidates = (low..high).toList()
        val weights = candidates.map { windowDecay(abs(it - effectiveBase), window) }
        val total = weights.sum()
        val pivot = random.nextDouble() * total
        var running = 0.0
        for (i in weights.indices) {
            running += weights[i]
            if (pivot <= running) return candidates[i]
        }
        return candidates.last()
    }

    private fun blendPsychometricChoice(anchorIndex: Int, optionCount: Int, probabilities: List<Double>?): Int {
        val anchor = anchorIndex.coerceIn(0, optionCount - 1)
        if (optionCount <= 0 || probabilities == null || probabilities.size != optionCount) return anchor
        val window = resolveFluctuationWindow(optionCount)
        if (window <= 0) return anchor
        val low = maxOf(0, anchor - window)
        val high = minOf(optionCount - 1, anchor + window)
        val adjusted = List(optionCount) { idx ->
            val w = probabilities[idx]
            val weight = if (w.isNaN() || w.isInfinite() || w <= 0.0) 0.0 else w
            when {
                weight <= 0.0 -> 0.0
                idx in low..high -> weight * windowDecay(abs(idx - anchor), window)
                else -> weight * (profile.consistencyOutsideDecay * 0.5)
            }
        }
        if (adjusted.sum() <= 0.0) return anchor
        return Probabilities.weightedIndex(Orientation_normalize(adjusted))
    }

    private fun Orientation_normalize(values: List<Double>): List<Double> {
        val total = values.sum()
        return if (total <= 0.0) values else values.map { it / total }
    }

    /** 获取带一致性倾向的选项索引。复刻 get_tendency_index（含 psycho_plan 路径）。 */
    fun getTendencyIndex(
        optionCount: Int,
        probabilities: List<Double>?,
        dimension: String?,
        psychoPlan: PsychoPlanLookup? = null,
        questionIndex: Int? = null,
        rowIndex: Int? = null,
    ): Int {
        if (optionCount <= 0) return 0
        fun finalize(choice: Int, anchor: Int?) =
            enforceZeroWeightGuard(choice, optionCount, probabilities, anchor)

        // 优先按心理测量计划取答案
        if (psychoPlan != null && questionIndex != null) {
            val choice = psychoPlan.getChoice(questionIndex, rowIndex)?.coerceIn(0, optionCount - 1)
            if (choice != null) {
                return if (psychoPlan.isLocked(questionIndex, rowIndex)) {
                    finalize(choice, choice)
                } else {
                    finalize(blendPsychometricChoice(choice, optionCount, probabilities), choice)
                }
            }
        }

        if (isUngrouped(dimension)) {
            val result = randomByProbabilities(optionCount, probabilities)
            return finalize(result, result)
        }
        val dim = dimension!!
        var baseRatio = dimensionBases[dim]
        if (baseRatio == null) {
            baseRatio = generateBaseRatio(optionCount, probabilities)
            dimensionBases[dim] = baseRatio
        }
        var base = (baseRatio * (optionCount - 1)).roundToInt()
        base = base.coerceIn(0, optionCount - 1)
        val selected = applyConsistency(base, optionCount, probabilities)
        return finalize(selected, base)
    }
}

/**
 * 分布矫正：根据运行时已提交分布，向目标比例收敛。复刻 resolve_distribution_probabilities。
 */
object DistributionCorrection {
    fun resolve(
        target: List<Double>,
        optionCount: Int,
        total: Int,
        counts: List<Int>,
        usePriorityProfile: Boolean,
        profile: ReliabilityProfile = DEFAULT_RELIABILITY_PROFILE,
    ): List<Double> {
        if (optionCount <= 0 || target.isEmpty() || total <= 0) return target
        val params = if (usePriorityProfile) {
            Quint(
                profile.distributionWarmupSamples.toDouble(),
                profile.distributionGain, profile.distributionMinFactor,
                profile.distributionMaxFactor, profile.distributionGapLimit,
            )
        } else {
            // 标准档：_STANDARD_CORRECTION_PARAMS = (12, 4.2, 0.45, 2.2, 0.42)
            Quint(12.0, 4.2, 0.45, 2.2, 0.42)
        }
        val sampleFactor = minOf(1.0, total.toDouble() / maxOf(1.0, params.warmup))
        if (sampleFactor <= 0.0) return target
        val adjusted = target.mapIndexed { idx, ratio ->
            if (ratio <= 0.0) return@mapIndexed 0.0
            val actual = if (idx < counts.size && total > 0) counts[idx].toDouble() / total else 0.0
            val gap = (ratio - actual).coerceIn(-params.gapLimit, params.gapLimit)
            var factor = exp(params.gain * sampleFactor * gap)
            factor = factor.coerceIn(params.minFactor, params.maxFactor)
            ratio * factor
        }
        val sum = adjusted.sum()
        return if (sum <= 0.0) target else adjusted.map { it / sum }
    }

    private data class Quint(
        val warmup: Double, val gain: Double, val minFactor: Double,
        val maxFactor: Double, val gapLimit: Double,
    )
}
