package com.surveycontroller.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.surveycontroller.android.core.questions.OfflineRehearsal
import com.surveycontroller.android.ui.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.surveycontroller.android.ui.components.StudioCard
import com.surveycontroller.android.ui.components.StudioEmblem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist

@Composable
internal fun OfflineRehearsalDialog(
    viewModel: MainViewModel,
    editableQuestions: Set<Int>,
    onDismiss: () -> Unit,
    onEditQuestion: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var seedText by remember { mutableStateOf(OfflineRehearsal.defaultSeed.toString()) }
    var report by remember { mutableStateOf<OfflineRehearsal.Report?>(null) }
    var issue by remember { mutableStateOf<OfflineRehearsal.Issue?>(null) }
    var generating by remember { mutableStateOf(false) }
    var selectedSample by remember { mutableIntStateOf(0) }
    var showDistribution by remember { mutableStateOf(false) }
    val seed = seedText.toLongOrNull()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.90f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StudioEmblem(Icons.Filled.Checklist)
                            Text("离线试跑", style = MaterialTheme.typography.headlineSmall)
                        }
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                }
                item { Text("本地预览最多 20 份样例，零提交。AI 填空以占位文本展示。") }
                item {
                    OutlinedTextField(
                        value = seedText,
                        onValueChange = { seedText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("随机种子") },
                        singleLine = true,
                        isError = seed == null,
                    )
                }
                item {
                    Button(
                        enabled = seed != null && !generating,
                        onClick = {
                            if (seed != null) {
                                generating = true
                                issue = null
                                report = null
                                scope.launch {
                                    try {
                                        val result = viewModel.rehearse(seed)
                                        selectedSample = 0
                                        report = result
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        issue = error as? OfflineRehearsal.Issue
                                            ?: OfflineRehearsal.Issue(error.message ?: "试跑失败")
                                    } finally {
                                        generating = false
                                    }
                                }
                            }
                        },
                    ) {
                        Text(if (report == null) "生成样例" else "重新生成")
                    }
                }
                if (generating) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                issue?.let { current ->
                    item {
                        Text(current.message.orEmpty(), color = MaterialTheme.colorScheme.error)
                        current.questionNumber?.takeIf { it in editableQuestions }?.let { number ->
                            TextButton(onClick = { onEditQuestion(number) }) { Text("编辑第 $number 题") }
                        }
                    }
                }
                report?.let { result ->
                    item {
                        Text("${result.samples.size} 份样例 · 种子 ${result.seed}", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !showDistribution, onClick = { showDistribution = false }, label = { Text("逐份样例") },
                            )
                            FilterChip(
                                selected = showDistribution, onClick = { showDistribution = true }, label = { Text("选项分布") },
                            )
                        }
                    }
                    if (showDistribution) {
                        items(result.distributions, key = { "distribution-${it.number}" }) { question ->
                            StudioCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    QuestionLink(question.number, question.title, question.number in editableQuestions, onEditQuestion)
                                    Text("${question.answeredCount}/${result.samples.size} 份样例作答")
                                    for ((label, count) in question.counts) {
                                        Text("$label · $count 份", style = MaterialTheme.typography.bodySmall)
                                        LinearProgressIndicator(
                                            progress = { count.toFloat() / question.answeredCount.coerceAtLeast(1) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val sample = result.samples[selectedSample]
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(enabled = selectedSample > 0, onClick = { selectedSample-- }) { Text("上一份") }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("样例 ${sample.number}/${result.samples.size}")
                                    sample.sourceRow?.let { Text("表格第 $it 行", style = MaterialTheme.typography.labelSmall) }
                                }
                                TextButton(enabled = selectedSample < result.samples.lastIndex, onClick = { selectedSample++ }) { Text("下一份") }
                            }
                        }
                        items(sample.questions, key = { "sample-${it.number}" }) { question ->
                            StudioCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    QuestionLink(question.number, question.title, question.number in editableQuestions, onEditQuestion)
                                    Text(
                                        question.answer,
                                        color = if (question.answered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    question.details.forEach { detail ->
                                        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionLink(number: Int, title: String, editable: Boolean, onEditQuestion: (Int) -> Unit) {
    val label = "第 $number 题 · $title"
    if (editable) {
        TextButton(onClick = { onEditQuestion(number) }) { Text(label) }
    } else {
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}
