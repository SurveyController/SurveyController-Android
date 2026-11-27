package com.surveycontroller.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.surveycontroller.android.data.SettingsStore
import com.surveycontroller.android.ui.MainViewModel
import kotlinx.coroutines.launch
import com.surveycontroller.android.ui.components.StudioCard
import com.surveycontroller.android.ui.components.StudioEmblem
import com.surveycontroller.android.ui.components.StudioHeading
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider

@Composable
fun SettingsScreen(viewModel: MainViewModel, onOpenIpUsage: () -> Unit = {}) {
    val store = viewModel.settingsStore
    val s by store.settings.collectAsState(initial = SettingsStore.Settings())
    val workbench by viewModel.workbench.collectAsState()
    val account by viewModel.account.collectAsState()
    val checking by viewModel.checkingUpdate.collectAsState()
    val toolMessage by viewModel.toolMessage.collectAsState()
    val logCount by viewModel.logFileCount.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cardCode by remember { mutableStateOf("") }
    var showResetDialog by remember { mutableStateOf(false) }
    val exportLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let(viewModel::exportLatestLog) }
    fun set(transform: (SettingsStore.Settings) -> SettingsStore.Settings) = scope.launch { store.update(transform) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StudioHeading("设置", "管理界面、运行偏好和本机数据。", Icons.Filled.Settings)

        // ===== 外观 =====
        SectionCard("外观") {
            Text("主题", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChip("跟随系统", s.themeMode == SettingsStore.ThemeMode.SYSTEM) { set { it.copy(themeMode = SettingsStore.ThemeMode.SYSTEM) } }
                ThemeChip("浅色", s.themeMode == SettingsStore.ThemeMode.LIGHT) { set { it.copy(themeMode = SettingsStore.ThemeMode.LIGHT) } }
                ThemeChip("深色", s.themeMode == SettingsStore.ThemeMode.DARK) { set { it.copy(themeMode = SettingsStore.ThemeMode.DARK) } }
            }
            SwitchRow(Icons.Filled.Palette, "动态取色", "跟随系统壁纸生成主题色（Android 12+）", s.dynamicColor) { set { st -> st.copy(dynamicColor = it) } }
            SwitchRow(Icons.Filled.Menu, "显示底部导航文字", "关闭后底部导航只显示图标", s.navigationLabelVisible) { set { st -> st.copy(navigationLabelVisible = it) } }
        }

        // ===== 行为 =====
        SectionCard("行为") {
            SwitchRow(Icons.Filled.Bedtime, "执行期间保持屏幕常亮", "任务运行时阻止屏幕自动熄灭，结束后自动恢复", s.preventSleepDuringRun) { set { st -> st.copy(preventSleepDuringRun = it) } }
            SwitchRow(Icons.Filled.Notifications, "任务完成/失败时通知", "应用不在前台时，任务结束弹出系统通知", s.taskResultNotification) { set { st -> st.copy(taskResultNotification = it) } }
            SwitchRow(Icons.AutoMirrored.Filled.Send, "提交结果遥测", "启用随机 IP 时，提交成功/失败结果上报服务端做统计，便于排查问题", s.submissionReportTelemetry) { set { st -> st.copy(submissionReportTelemetry = it) } }
            SwitchRow(Icons.Filled.Description, "自动保存运行日志", "任务结束后保留本次运行日志，仅保留最近若干份历史记录", s.autoSaveLogs) { set { st -> st.copy(autoSaveLogs = it) } }
            if (s.autoSaveLogs) {
                Row(Modifier.fillMaxWidth().padding(start = 34.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("保留最近日志份数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    RetentionDropdown(s.logRetentionCount) { v -> set { st -> st.copy(logRetentionCount = v) } }
                }
            }
        }

        // ===== 更新 =====
        SectionCard("更新") {
            SwitchRow(Icons.Filled.SystemUpdate, "启动时检查更新", "新版本更稳定、功能更多，建议开启", s.autoCheckUpdate) { set { st -> st.copy(autoCheckUpdate = it) } }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("立即检查（结果见“关于”页）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (checking) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else OutlinedButton(onClick = viewModel::checkUpdate) { Text("检查更新") }
            }
        }

        // ===== 默认运行参数 =====
        SectionCard("默认运行参数") {
            com.surveycontroller.android.ui.components.Stepper(
                label = "默认目标份数", value = s.defaultTarget,
                onValueChange = { v -> set { st -> st.copy(defaultTarget = v) } },
                range = 1..9999, step = 1, suffix = "份",
            )
            com.surveycontroller.android.ui.components.Stepper(
                label = "默认并发数", value = s.defaultThreads,
                onValueChange = { v -> set { st -> st.copy(defaultThreads = v) } },
                range = 1..16, step = 1,
            )
        }

        // ===== AI 填空 =====
        SectionCard("AI 填空") {
            val importedAi = workbench.draft?.preserved?.importedAiConfig
            if (importedAi?.present == true) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("已导入桌面 AI 配置", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "自定义 API · ${importedAi.provider.ifBlank { "provider" }} · ${importedAi.model.ifBlank { "未指定模型" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = viewModel::applyImportedAiConfig) { Text("采用") }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("启用自定义 AI", modifier = Modifier.weight(1f))
                Switch(checked = s.aiEnabled, onCheckedChange = { enabled -> set { it.copy(aiEnabled = enabled) } })
            }
            if (s.aiEnabled) {
                OutlinedTextField(s.aiBaseUrl, { v -> set { it.copy(aiBaseUrl = v) } }, label = { Text("API Base URL") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(s.aiApiKey, { v -> set { it.copy(aiApiKey = v) } }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(s.aiModel, { v -> set { it.copy(aiModel = v) } }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("API 协议", style = MaterialTheme.typography.bodyMedium)
                        Text("自动模式会先尝试 Chat，端点不匹配时退到 Responses", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AiProtocolDropdown(s.aiApiProtocol) { v -> set { it.copy(aiApiProtocol = v) } }
                }
                OutlinedTextField(s.aiSystemPrompt, { v -> set { it.copy(aiSystemPrompt = v) } }, label = { Text("系统提示词（可选）") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }

        // ===== 随机 IP 账号 =====
        SectionCard("随机 IP 账号") {
            val sess = account.session
            if (sess.authenticated) {
                Text("用户 ID：${sess.userId}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (sess.ready) "登录状态：已激活" else "登录状态：旧版会话（缺少登录凭据，请点“同步额度”升级）",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("额度：剩余 ${fmt(sess.remainingQuota)} / 总 ${fmt(sess.totalQuota)}（已用 ${fmt(sess.usedQuota)}）", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("尚未激活随机 IP 账号。可使用额度卡密直接激活；服务端已停止发放免费试用，旧版“领取试用”可能失败。", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!sess.authenticated) Button(onClick = viewModel::activateTrial, enabled = !account.busy) { Text("激活 / 领取") }
                else OutlinedButton(onClick = viewModel::refreshQuota, enabled = !account.busy) { Text("同步额度") }
                OutlinedButton(onClick = viewModel::checkBackendStatus, enabled = !account.busy) { Text("服务状态") }
                if (account.busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            OutlinedButton(onClick = onOpenIpUsage, modifier = Modifier.padding(top = 4.dp)) { Text("查看 IP 使用记录") }
            OutlinedTextField(
                cardCode,
                { cardCode = it },
                label = { Text(if (sess.authenticated) "额度卡密" else "额度卡密（未激活时用于激活）") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
            )
            Button(
                onClick = { viewModel.redeemCard(cardCode); cardCode = "" },
                enabled = !account.busy && cardCode.isNotBlank(),
                modifier = Modifier.padding(top = 4.dp),
            ) { Text(if (sess.authenticated) "兑换额度" else "激活账号") }
            account.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
        }

        // ===== 系统工具 =====
        SectionCard("系统工具") {
            // 日志目录信息
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("运行日志目录", style = MaterialTheme.typography.bodyLarge)
                    Text("已归档 $logCount 份 · ${viewModel.logsDirectoryPath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 34.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLogLauncher.launch("survey_log_${System.currentTimeMillis()}.txt") }, enabled = logCount > 0) { Text("导出最近日志") }
                OutlinedButton(onClick = viewModel::clearLogs, enabled = logCount > 0) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  清除日志")
                }
            }

            // 恢复默认设置
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("恢复默认设置", style = MaterialTheme.typography.bodyLarge)
                    Text("将所有设置项还原到初始状态", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { showResetDialog = true }) { Text("恢复默认") }
            }

            toolMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复默认设置") },
            text = { Text("确定要恢复默认设置吗？\n这将还原所有设置项到初始状态。") },
            confirmButton = {
                TextButton(onClick = { viewModel.resetSettings(); showResetDialog = false }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    StudioCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, content: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StudioEmblem(icon)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun RetentionDropdown(value: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$value 份")
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SettingsStore.LOG_RETENTION_OPTIONS.forEach { opt ->
                DropdownMenuItem(text = { Text("$opt 份") }, onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}

@Composable
private fun AiProtocolDropdown(value: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val normalized = SettingsStore.normalizeAiApiProtocol(value)
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(aiProtocolLabel(normalized))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SettingsStore.AI_API_PROTOCOL_OPTIONS.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(aiProtocolLabel(opt)) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun aiProtocolLabel(value: String): String =
    when (SettingsStore.normalizeAiApiProtocol(value)) {
        SettingsStore.AI_API_PROTOCOL_CHAT_COMPLETIONS -> "Chat"
        SettingsStore.AI_API_PROTOCOL_RESPONSES -> "Responses"
        else -> "自动"
    }

private fun fmt(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)
