package com.surveycontroller.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import com.surveycontroller.android.core.engine.RunStatus
import com.surveycontroller.android.core.engine.SlotStatus
import com.surveycontroller.android.ui.MainViewModel
import com.surveycontroller.android.ui.components.StudioCard
import com.surveycontroller.android.ui.components.studioFeature
import com.surveycontroller.android.ui.components.StudioTopBar
import com.surveycontroller.android.ui.components.studioSuccessColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val progress by viewModel.runProgress.collectAsState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { StudioTopBar("运行控制台", onBack) },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OverviewCard(progress) }
            item { ControlRow(progress.status, viewModel) }
            if (progress.failureReasons.isNotEmpty()) item { FailureBreakdownCard(progress.failureReasons) }
            if (progress.slotStatuses.isNotEmpty()) {
                item { Text("并发槽", style = MaterialTheme.typography.titleSmall) }
                items(progress.slotStatuses.values.sortedBy { it.name }) { SlotCard(it) }
            }
            item { Text("运行日志", style = MaterialTheme.typography.titleSmall) }
            item { LogCard(progress.recentLogs) }
        }
    }
}

@Composable
private fun OverviewCard(p: com.surveycontroller.android.core.engine.RunProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().studioFeature().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SuccessRing(p.success, p.target)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(statusLabel(p.status), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                if (p.message.isNotBlank()) Text(p.message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                val seconds = p.elapsedMs / 1000
                val rate = if (seconds > 0) "%.1f".format(p.success * 60.0 / seconds) else "—"
                Text("用时 ${formatDuration(p.elapsedMs)} · $rate 份/分", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("成功", p.success.toString(), Modifier.weight(1f), valueColor = studioSuccessColor())
            Stat(
                "失败", p.fail.toString(), Modifier.weight(1f),
                valueColor = if (p.fail > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Stat("目标", p.target.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SuccessRing(success: Int, target: Int) {
    val fraction = if (target > 0) (success.toFloat() / target).coerceIn(0f, 1f) else 0f
    val track = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = if (fraction >= 1f) studioSuccessColor() else MaterialTheme.colorScheme.primary
    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 5.dp.toPx()
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            val style = Stroke(5.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = style)
            if (fraction > 0f) drawArc(progressColor, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = style)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("完成", style = MaterialTheme.typography.labelSmall, color = progressColor)
        }
    }
}

@Composable
private fun ControlRow(status: RunStatus, viewModel: MainViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val running = status == RunStatus.RUNNING
        val paused = status == RunStatus.PAUSED
        if (running) OutlinedButton(onClick = viewModel::pauseRun, modifier = Modifier.weight(1f)) { Text("暂停") }
        if (paused) OutlinedButton(onClick = viewModel::resumeRun, modifier = Modifier.weight(1f)) { Text("继续") }
        if (running || paused) Button(
            onClick = viewModel::stopRun,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Text("停止") }
    }
}

@Composable
private fun FailureBreakdownCard(reasons: Map<String, Int>) {
    val total = reasons.values.sum().coerceAtLeast(1)
    StudioCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("失败原因", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            reasons.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(reason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.32f))
                    Box(
                        Modifier.weight(0.5f).height(8.dp).clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(count.toFloat() / total).height(8.dp)
                                .background(MaterialTheme.colorScheme.error),
                        )
                    }
                    Text("$count", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.12f).padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SlotCard(slot: SlotStatus) {
    StudioCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (slot.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            )
            Spacer(Modifier.size(10.dp))
            Text(slot.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.3f))
            Text(slot.step, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f))
            Text("✓${slot.success} ✗${slot.fail}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.3f))
        }
    }
}

@Composable
private fun LogCard(logs: List<String>) {
    StudioCard(Modifier.fillMaxWidth().height(250.dp)) {
        if (logs.isEmpty()) {
            Text("暂无日志", Modifier.padding(15.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), reverseLayout = true) {
            items(logs.asReversed()) { line ->
                Text(line, Modifier.fillMaxWidth().padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    StudioCard(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, color = valueColor)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val sec = s % 60
    return if (m > 0) "${m}分${sec}秒" else "${sec}秒"
}

private fun statusLabel(status: RunStatus): String = when (status) {
    RunStatus.IDLE -> "空闲"
    RunStatus.RUNNING -> "运行中"
    RunStatus.PAUSED -> "已暂停"
    RunStatus.STOPPING -> "停止中"
    RunStatus.FINISHED -> "已完成"
    RunStatus.FAILED -> "已停止"
}
