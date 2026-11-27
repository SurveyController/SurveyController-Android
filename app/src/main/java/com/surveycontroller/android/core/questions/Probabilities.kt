package com.surveycontroller.android.core.questions

import kotlin.math.abs
import kotlin.random.Random

/**
 * 概率/权重抽样工具。1:1 复刻 software/core/questions/utils.py 的核心函数。
 */
object Probabilities {

    /** 按权重随机选择索引。复刻 weighted_index：仅命中正权重区间。 */
    fun weightedIndex(probabilities: List<Double>, random: Random = Random.Default): Int {
        require(probabilities.isNotEmpty()) { "probabilities cannot be empty" }
        val weights = probabilities.map { v ->
            val w = if (v.isNaN() || v.isInfinite() || v < 0.0) 0.0 else v
            w
        }
        val total = weights.sum()
        if (total <= 0.0) return random.nextInt(weights.size)
        val pivot = random.nextDouble() * total
        var running = 0.0
        var lastPositive = 0
        for (i in weights.indices) {
            if (weights[i] <= 0.0) continue
            running += weights[i]
            lastPositive = i
            if (pivot < running) return i
        }
        return lastPositive
    }

    /** 归一化（总和必须 > 0）。 */
    fun normalize(values: List<Double>): List<Double> {
        require(values.isNotEmpty()) { "概率列表不能为空" }
        val total = values.sum()
        require(total > 0) { "概率列表的和必须大于0" }
        return values.map { it / total }
    }

    /**
     * 归一化下拉/单选概率配置，对齐到选项数。
     * probConfig 可能是 List<Double>、单值、null/-1（表示均匀）。复刻 normalize_droplist_probs。
     */
    fun normalizeDroplistProbs(probConfig: Any?, optionCount: Int): List<Double> {
        if (optionCount <= 0) return emptyList()
        if (probConfig == null || (probConfig is Number && probConfig.toInt() == -1)) {
            return List(optionCount) { 1.0 / optionCount }
        }
        val base: List<Double> = when (probConfig) {
            is List<*> -> probConfig.map { (it as? Number)?.toDouble() ?: 0.0 }
            is Number -> listOf(probConfig.toDouble())
            else -> emptyList()
        }
        val sanitized = MutableList(optionCount) { i ->
            val v = base.getOrNull(i) ?: 0.0
            if (v < 0.0) 0.0 else v
        }
        val total = sanitized.sum()
        return if (total > 0) sanitized.map { it / total } else List(optionCount) { 1.0 / optionCount }
    }

    fun stochasticRound(value: Double, random: Random = Random.Default): Int {
        if (!value.isFinite() || value <= 0.0) return 0
        val lower = kotlin.math.floor(value).toInt()
        val fraction = (value - lower).coerceIn(0.0, 1.0)
        return if (random.nextDouble() < fraction) lower + 1 else lower
    }

    /** 按权重不放回抽样。复刻 weighted_sample_without_replacement。 */
    fun weightedSampleWithoutReplacement(
        indices: List<Int>,
        weights: List<Double>,
        count: Int,
        random: Random = Random.Default,
    ): List<Int> {
        if (count <= 0) return emptyList()
        val pool = indices.zip(weights)
            .filter { (_, w) -> w.isFinite() && w > 0.0 }
            .map { (i, w) -> mutableListOf(i.toDouble(), w) }
            .toMutableList()
        if (pool.isEmpty()) return emptyList()
        val selected = mutableListOf<Int>()
        val target = minOf(count, pool.size)
        while (pool.isNotEmpty() && selected.size < target) {
            val total = pool.sumOf { it[1] }
            if (total <= 0.0) break
            val pivot = random.nextDouble() * total
            var running = 0.0
            var chosen = pool.size - 1
            for (idx in pool.indices) {
                running += pool[idx][1]
                if (pivot < running) {
                    chosen = idx
                    break
                }
            }
            selected.add(pool.removeAt(chosen)[0].toInt())
        }
        return selected
    }

    /** 按权重值分层（高→低）。复刻 build_rank_groups。 */
    fun buildRankGroups(probabilities: List<Double>): List<List<Int>> {
        val buckets = sortedMapOf<Double, MutableList<Int>>(compareByDescending { it })
        probabilities.forEachIndexed { idx, raw ->
            val w = if (raw.isFinite() && raw > 0.0) raw else 0.0
            if (w > 0.0) buckets.getOrPut(w) { mutableListOf() }.add(idx)
        }
        return buckets.values.toList()
    }

    /** 按参考权重层级约束结果，避免低层反超高层。复刻 enforce_reference_rank_order。 */
    fun enforceReferenceRankOrder(probabilities: List<Double>, reference: List<Double>): List<Double> {
        val adjusted = probabilities.map { if (it.isFinite() && it > 0.0) it else 0.0 }.toMutableList()
        val groups = buildRankGroups(reference)
        if (groups.size <= 1) return adjusted
        var previousFloor: Double? = null
        for (group in groups) {
            var groupValues = group.mapNotNull { adjusted.getOrNull(it) }
            if (groupValues.isEmpty()) continue
            previousFloor?.let { floor ->
                val clamped = maxOf(0.0, floor)
                for (idx in group) if (idx in adjusted.indices) adjusted[idx] = minOf(adjusted[idx], clamped)
                groupValues = group.mapNotNull { adjusted.getOrNull(it) }
            }
            if (groupValues.isNotEmpty()) {
                val currentMin = groupValues.min()
                previousFloor = previousFloor?.let { minOf(it, currentMin) } ?: currentMin
            }
        }
        val total = adjusted.sum()
        return if (total <= 0.0) adjusted else adjusted.map { it / total }
    }

    fun distanceTo(target: Int, idx: Int): Int = abs(idx - target)
}
