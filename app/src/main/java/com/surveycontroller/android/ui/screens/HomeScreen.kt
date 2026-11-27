package com.surveycontroller.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surveycontroller.android.provider.SurveyProviderType
import com.surveycontroller.android.ui.MainViewModel
import com.surveycontroller.android.ui.components.StudioCard
import com.surveycontroller.android.ui.components.StudioEmblem
import com.surveycontroller.android.ui.components.studioFeature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.surveycontroller.android.ui.components.studioActionColors
import com.surveycontroller.android.ui.theme.ChromeDarkColors
import com.surveycontroller.android.ui.theme.LocalChromeDarkTheme

@Composable
fun HomeScreen(viewModel: MainViewModel, onConfigure: () -> Unit, onScan: () -> Unit) {
    val state by viewModel.workbench.collectAsState()
    val recent by viewModel.recentSurveys.collectAsState()
    val importConfig = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importConfig(uri)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().statusBarsPadding()
                .verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().studioFeature().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("问卷工作台", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineLarge)
                        Text("解析问卷链接，配置答案并离线试跑。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    StudioEmblem(Icons.Filled.Description)
                }
                OutlinedTextField(
                    value = state.url,
                    onValueChange = viewModel::setUrl,
                    label = { Text("问卷链接") },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    maxLines = 3,
                )
                val input = state.url.trim()
                val supported = input.isNotEmpty() && viewModel.supportedUrl(input)
                if (input.isNotEmpty()) {
                    Text(
                        if (supported) "已识别 · ${platformLabel(SurveyProviderType.detect(input).id)}"
                        else "请使用问卷星、腾讯问卷或 Credamo 见数的链接",
                        color = if (supported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Button(
                    colors = studioActionColors(),
                    onClick = viewModel::parse,
                    enabled = !state.parsing && input.isNotEmpty(),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.parsing) CircularProgressIndicator(Modifier.size(17.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 2.dp)
                        Text(if (state.parsing) "正在解析" else "解析问卷")
                        if (!state.parsing) Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(17.dp))
                    }
                }
            }

            state.error?.let { error ->
                StudioCard(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(error, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            state.draft?.let { draft ->
                StudioCard(Modifier.fillMaxWidth().clickable(onClick = onConfigure)) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        StudioEmblem(Icons.Filled.CheckCircle)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(draft.definition.title.ifBlank { "已解析问卷" }, style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${platformLabel(draft.definition.provider.id)} · ${draft.questions.size} 题",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("继续配置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceTile("扫描二维码", Icons.Filled.QrCodeScanner, Modifier.weight(1f), onScan)
                SourceTile("导入配置", Icons.Filled.FolderOpen, Modifier.weight(1f)) {
                    importConfig.launch(arrayOf("application/json", "text/plain"))
                }
            }

            if (recent.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("最近问卷", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = viewModel::clearHistory) { Text("清空") }
                }
                recent.forEach { entry ->
                    StudioCard(Modifier.fillMaxWidth().clickable { viewModel.useRecent(entry.url) }) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StudioEmblem(Icons.Filled.History)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.title.ifBlank { entry.url }, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${platformLabel(entry.provider)} · ${entry.questionCount} 题 · ${formatTime(entry.timestamp)}",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Text(
                "仅供已获授权的问卷测试使用。",
                style = MaterialTheme.typography.bodySmall,
                color = if (LocalChromeDarkTheme.current) ChromeDarkColors.muted else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceTile(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    StudioCard(modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            StudioEmblem(icon)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun platformLabel(id: String): String = when (id) {
    "wjx" -> "问卷星"
    "qq" -> "腾讯问卷"
    "credamo" -> "Credamo 见数"
    else -> id
}
