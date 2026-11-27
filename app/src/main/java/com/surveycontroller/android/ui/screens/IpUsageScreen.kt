package com.surveycontroller.android.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.surveycontroller.android.core.network.MonotoneCubic
import com.surveycontroller.android.ui.MainViewModel
import com.surveycontroller.android.ui.components.StudioCard
import com.surveycontroller.android.ui.components.studioFeature
import com.surveycontroller.android.ui.components.StudioTopBar
import androidx.compose.material3.Scaffold

/**
 * IP 使用记录页。1:1 对齐桌面端 ip_usage_page：每日提取 IP 数折线图 + IP 池剩余数量。
 * 折线采用单调三次插值平滑（MonotoneCubic），进入页面自动加载并触发彩蛋福利领取。
 */
@Composable
fun IpUsageScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.ipUsage.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadIpUsage()
        viewModel.claimEasterEggBonus()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            StudioTopBar("IP 使用记录", onBack, actions = {
                IconButton(onClick = viewModel::loadIpUsage, enabled = !state.loading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            })
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().studioFeature().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("IP 池剩余", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    val remaining = state.summary?.remainingIp
                    Text(
                        when {
                            state.loading && state.summary == null -> "同步中"
                            remaining != null -> remaining.toString()
                            else -> "未知"
                        },
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (state.loading) CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
            }

            state.bonusMessage?.let {
                StudioCard(Modifier.fillMaxWidth()) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
                }
            }

            // 折线图卡片
            StudioCard(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("每日提取 IP 数", style = MaterialTheme.typography.titleMedium)
                    val records = state.summary?.records.orEmpty()
                    when {
                        state.error != null -> CenterHint("加载失败：${state.error}")
                        state.loading && records.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        records.isEmpty() -> CenterHint("暂无数据")
                        else -> {
                            UsageLineChart(
                                values = records.map { it.total },
                                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                            )
                            Text(
                                "${records.first().label} ~ ${records.last().label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UsageLineChart(values: List<Int>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val fill = line.copy(alpha = 0.12f)
    val dot = MaterialTheme.colorScheme.primary
    Canvas(modifier.height(280.dp)) {
        if (values.isEmpty()) return@Canvas
        val n = values.size
        val maxV = (values.max().coerceAtLeast(1)).toDouble()
        // Y 轴上界对齐桌面端：向上取整到 1000 的倍数
        val top = maxOf(1000.0, kotlin.math.ceil(maxV / 1000.0) * 1000.0).let { if (it == maxV) it + 1000 else it }
        val padL = 8f
        val padR = 8f
        val padB = 8f
        val w = size.width - padL - padR
        val h = size.height - padB

        // 网格线（4 条横线）
        for (i in 0..4) {
            val y = h * i / 4f
            drawLine(grid, Offset(padL, y), Offset(size.width - padR, y), strokeWidth = 1f)
        }

        fun px(idx: Int): Float = if (n == 1) padL + w / 2f else padL + w * idx / (n - 1)
        fun py(v: Double): Float = (h - (v / top) * h).toFloat()

        val xs = (0 until n).map { it.toDouble() }
        val ys = values.map { it.toDouble() }
        val smooth = MonotoneCubic.interpolate(xs, ys, segments = 16)

        // 平滑路径
        val path = Path()
        val areaPath = Path()
        smooth.forEachIndexed { i, (x, y) ->
            val sx = if (n == 1) padL + w / 2f else padL + (w * x / (n - 1)).toFloat()
            val sy = py(y)
            if (i == 0) {
                path.moveTo(sx, sy)
                areaPath.moveTo(sx, h)
                areaPath.lineTo(sx, sy)
            } else {
                path.lineTo(sx, sy)
                areaPath.lineTo(sx, sy)
            }
        }
        val lastX = if (n == 1) padL + w / 2f else size.width - padR
        areaPath.lineTo(lastX, h)
        areaPath.close()
        drawPath(areaPath, fill)
        drawPath(path, line, style = Stroke(width = 3f))

        // 数据点
        for (i in 0 until n) {
            drawCircle(dot, radius = 4f, center = Offset(px(i), py(ys[i])))
        }
    }
}
