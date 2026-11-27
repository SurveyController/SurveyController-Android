package com.surveycontroller.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 联动占比编辑器。占比即百分比，所有选项合计恒 = 100%：
 *  - 拖动/输入任一项 → 其余项按原比例自动补足，合计始终 100%（无需手动归一化）；
 *  - 每项一个滑块 + 数字框（可直接键入精确百分比）；
 *  - 顶部彩色分布条直观展示占比，「均分」一键平均。
 */
@Composable
fun WeightEditor(
    labels: List<String>,
    weights: List<Int>,
    onChange: (List<Int>) -> Unit,
    previewPrefix: String = "",
) {
    val normalized = normalizeTo100(weights)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { DistributionBar(normalized) }
            TextButton(onClick = { onChange(evenSplit(labels.size)) }) {
                Icon(Icons.Filled.Balance, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" 均分", style = MaterialTheme.typography.labelMedium)
            }
        }

        labels.forEachIndexed { i, label ->
            val pct = normalized.getOrElse(i) { 0 }
            Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label.ifEmpty { "选项${i + 1}" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "$pct%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = pct.toFloat().coerceIn(0f, 100f),
                        onValueChange = { v -> onChange(redistribute(normalized, i, v.roundToInt(), emptySet())) },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = pct.toString(),
                        onValueChange = { s ->
                            val v = s.filter { it.isDigit() }.take(3).toIntOrNull()?.coerceIn(0, 100) ?: 0
                            onChange(redistribute(normalized, i, v, emptySet()))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        suffix = { Text("%", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.width(96.dp).padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DistributionBar(values: List<Int>) {
    val total = values.sum().coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(12.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(6.dp)),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        values.forEachIndexed { i, v ->
            if (v > 0) Box(Modifier.weight(v.toFloat() / total).fillMaxHeight().background(distributionColors[i % distributionColors.size]))
        }
    }
}
