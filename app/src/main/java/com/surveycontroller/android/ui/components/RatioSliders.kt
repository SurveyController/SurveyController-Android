package com.surveycontroller.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 联动占比滑块组：拖动任一项，其余「未锁定」项按原比例自动补足，总和恒为 100%，实时显示百分比。
 * 复刻桌面端 ratio_slider.py 的交互，并增强：
 *  - 每项可「锁定」：拖其他项时被锁项保持不变；
 *  - 顶部一条「分布预览」彩色条，直观展示各项占比。
 */
@Composable
fun RatioSliders(
    labels: List<String>,
    values: List<Int>,
    onChange: (List<Int>) -> Unit,
    locked: Set<Int> = emptySet(),
    onToggleLock: ((Int) -> Unit)? = null,
) {
    val normalized = remember(values) { normalizeTo100(values) }
    var editing by remember { mutableStateOf(-1) }
    var editText by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { DistributionBar(normalized) }
            onChange.let { change ->
                TextButton(onClick = { change(evenSplit(labels.size)) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.width(16.dp))
                    Text("均分", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        labels.forEachIndexed { i, label ->
            val isLocked = i in locked
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label.ifEmpty { "选项${i + 1}" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(84.dp),
                )
                if (onToggleLock != null) {
                    IconButton(onClick = { onToggleLock(i) }, modifier = Modifier.width(36.dp)) {
                        Icon(
                            if (isLocked) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                            contentDescription = if (isLocked) "已锁定" else "未锁定",
                            tint = if (isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Slider(
                    value = normalized.getOrElse(i) { 0 }.toFloat(),
                    onValueChange = { v -> if (!isLocked) onChange(redistribute(normalized, i, roundToStep(v), locked)) },
                    valueRange = 0f..100f,
                    steps = 19, // 5% 吸附
                    enabled = !isLocked,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                Text(
                    "${normalized.getOrElse(i) { 0 }}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp).clickable(enabled = !isLocked) {
                        editing = i; editText = normalized.getOrElse(i) { 0 }.toString()
                    },
                )
            }
        }

        if (editing >= 0) {
            val idx = editing
            AlertDialog(
                onDismissRequest = { editing = -1 },
                title = { Text("设置「${labels.getOrElse(idx) { "选项${idx + 1}" }}」占比") },
                text = {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { s -> editText = s.filter { it.isDigit() }.take(3) },
                        suffix = { Text("%") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val v = editText.toIntOrNull()?.coerceIn(0, 100)
                        if (v != null) onChange(redistribute(normalized, idx, v, locked))
                        editing = -1
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { editing = -1 }) { Text("取消") } },
            )
        }
    }
}

/** 分布预览条：按占比拼接彩色段。 */
@Composable
private fun DistributionBar(values: List<Int>) {
    Row(
        Modifier.fillMaxWidth().height(10.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(5.dp)),
    ) {
        values.forEachIndexed { i, v ->
            if (v > 0) {
                Box(
                    Modifier.weight(v.toFloat()).fillMaxHeight()
                        .background(distributionColors[i % distributionColors.size]),
                )
            }
        }
    }
}

/**
 * 拖动第 changed 项到 newValue 后，在「未锁定且非自身」的项之间按原比例重分配，使总和=100。
 * 被锁定项保持不变；锁定额度从可分配总额中扣除。
 */
fun redistribute(current: List<Int>, changed: Int, newValueRaw: Int, locked: Set<Int> = emptySet()): List<Int> {
    val n = current.size
    if (n == 0) return current
    if (n == 1) return listOf(100)
    val lockedSum = (0 until n).filter { it in locked && it != changed }.sumOf { current[it] }
    // 改动项最多只能占到「100 - 锁定额度」
    val maxForChanged = (100 - lockedSum).coerceAtLeast(0)
    val newValue = newValueRaw.coerceIn(0, maxForChanged)
    val values = current.toMutableList()
    values[changed] = newValue
    val freeIdx = (0 until n).filter { it != changed && it !in locked }
    if (freeIdx.isEmpty()) {
        // 没有可调整项：把差额补回改动项
        values[changed] = maxForChanged
        return values
    }
    val remaining = (100 - lockedSum - newValue).coerceAtLeast(0)
    val freeSum = freeIdx.sumOf { current[it] }
    if (freeSum > 0) {
        for (k in freeIdx) values[k] = (remaining * current[k].toDouble() / freeSum).toInt()
    } else {
        val each = remaining / freeIdx.size
        for ((j, k) in freeIdx.withIndex()) values[k] =
            if (j == freeIdx.size - 1) remaining - each * (freeIdx.size - 1) else each
    }
    // 整数舍入误差补到第一个可调整项
    val total = values.sum()
    if (total != 100) values[freeIdx[0]] = (values[freeIdx[0]] + (100 - total)).coerceAtLeast(0)
    return values
}

/** 将任意权重展示归一化为总和=100 的整数。 */
fun normalizeTo100(values: List<Int>): List<Int> {
    val n = values.size
    if (n == 0) return values
    val total = values.sum()
    if (total == 100) return values
    val result = if (total > 0) {
        values.map { (it * 100.0 / total).toInt() }.toMutableList()
    } else {
        MutableList(n) { if (it < n - 1) 100 / n else 100 - (100 / n) * (n - 1) }
    }
    val diff = 100 - result.sum()
    if (diff != 0) result[0] = (result[0] + diff).coerceAtLeast(0)
    return result
}

/** 均分为总和=100（首项补差）。 */
fun evenSplit(n: Int): List<Int> {
    if (n <= 0) return emptyList()
    val each = 100 / n
    return List(n) { if (it < n - 1) each else 100 - each * (n - 1) }
}

/** 把滑块值吸附到最近的 5% 刻度。 */
fun roundToStep(value: Float, step: Int = 5): Int =
    ((value / step).roundToInt() * step).coerceIn(0, 100)

/**
 * 多选「命中概率」预览条：每项独立 0–100%，按概率深浅着色（不归一化）。
 */
@Composable
fun HitRateBar(values: List<Int>) {
    val base = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().height(10.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(5.dp)),
    ) {
        values.forEach { v ->
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(base.copy(alpha = (0.18f + 0.82f * (v.coerceIn(0, 100) / 100f)))),
            )
        }
    }
}
