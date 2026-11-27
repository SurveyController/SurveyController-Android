package com.surveycontroller.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surveycontroller.android.core.model.QuestionEntryType
import com.surveycontroller.android.data.QuestionConfigDraft
import com.surveycontroller.android.ui.MainViewModel
import com.surveycontroller.android.ui.components.StudioCard
import com.surveycontroller.android.ui.components.StudioTopBar
import com.surveycontroller.android.ui.components.StudioHeading
import com.surveycontroller.android.ui.components.studioActionColors

/**
 * 条件规则可视化编辑器：当条件题选中/未选某些选项时，约束目标题必选/禁选某些选项。
 * 对应桌面端的条件规则配置，数据写入 draft.answerRules，运行时由 ConsistencyEngine 生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnswerRulesScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.workbench.collectAsState()
    val draft = state.draft
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { StudioTopBar("条件规则", onBack, draft?.definition?.title) },
    ) { inner ->
        if (draft == null) {
            Text("请先解析问卷", modifier = Modifier.padding(inner).padding(16.dp))
            return@Scaffold
        }
        // 仅单选/多选/量表/矩阵可作为条件或目标（对应 _SUPPORTED_RULE_TYPE_CODES = 3/4/5/6）
        val eligible = draft.questions.filter {
            it.entryType in listOf(
                QuestionEntryType.SINGLE, QuestionEntryType.MULTIPLE,
                QuestionEntryType.SCALE, QuestionEntryType.SCORE, QuestionEntryType.MATRIX,
            )
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StudioHeading("规则编辑器", "按条件题的答案限制后续题目。") }
            item { RuleBuilder(eligible, onAdd = viewModel::addAnswerRule) }
            item { Text("已配置 · ${draft.answerRules.size} 条", style = MaterialTheme.typography.titleLarge) }
            items(draft.answerRules) { rule ->
                val index = draft.answerRules.indexOf(rule)
                RuleCard(rule, eligible) { viewModel.removeAnswerRule(index) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleBuilder(questions: List<QuestionConfigDraft>, onAdd: (Map<String, Any?>) -> Unit) {
    if (questions.size < 2) {
        StudioCard { Text("可作为条件/目标的题目不足 2 道", Modifier.padding(16.dp)) }
        return
    }
    var conditionQ by remember { mutableStateOf(questions.first()) }
    var conditionMode by remember { mutableStateOf("selected") }
    var conditionRowIndex by remember { mutableStateOf<Int?>(defaultRowIndex(questions.first())) }
    val conditionOpts = remember { mutableStateListOf<Int>() }
    var targetQ by remember { mutableStateOf(questions.last()) }
    var actionMode by remember { mutableStateOf("must_select") }
    var targetRowIndex by remember { mutableStateOf<Int?>(defaultRowIndex(questions.last())) }
    val targetOpts = remember { mutableStateListOf<Int>() }

    StudioCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("新增规则", style = MaterialTheme.typography.titleMedium)

            Text("条件题（必须排在目标题之前）", style = MaterialTheme.typography.labelMedium)
            QuestionChips(questions, conditionQ) {
                conditionQ = it
                conditionRowIndex = defaultRowIndex(it)
                conditionOpts.clear()
            }
            RowChips(conditionQ, conditionRowIndex) {
                conditionRowIndex = it
                conditionOpts.clear()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(conditionMode == "selected", { conditionMode = "selected" }, { Text("选中") })
                FilterChip(conditionMode == "not_selected", { conditionMode = "not_selected" }, { Text("未选中") })
            }
            Text("条件选项", style = MaterialTheme.typography.labelMedium)
            OptionChips(conditionQ, conditionOpts)

            Text("目标题", style = MaterialTheme.typography.labelMedium)
            QuestionChips(questions, targetQ) {
                targetQ = it
                targetRowIndex = defaultRowIndex(it)
                targetOpts.clear()
            }
            RowChips(targetQ, targetRowIndex) {
                targetRowIndex = it
                targetOpts.clear()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(actionMode == "must_select", { actionMode = "must_select" }, { Text("必选") })
                FilterChip(actionMode == "must_not_select", { actionMode = "must_not_select" }, { Text("禁选") })
            }
            Text("目标选项", style = MaterialTheme.typography.labelMedium)
            OptionChips(targetQ, targetOpts)

            Button(
                colors = studioActionColors(),
                onClick = {
                    val rule = mutableMapOf<String, Any?>(
                            "condition_question_num" to conditionQ.num,
                            "condition_mode" to conditionMode,
                            "condition_option_indices" to conditionOpts.sorted().toList(),
                            "target_question_num" to targetQ.num,
                            "action_mode" to actionMode,
                            "target_option_indices" to targetOpts.sorted().toList(),
                    )
                    conditionRowIndex?.let { rule["condition_row_index"] = it }
                    targetRowIndex?.let { rule["target_row_index"] = it }
                    onAdd(rule)
                    conditionOpts.clear(); targetOpts.clear()
                },
                enabled = conditionQ.num < targetQ.num && conditionOpts.isNotEmpty() && targetOpts.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("添加规则") }
            if (conditionQ.num >= targetQ.num) {
                Text("条件题题号需小于目标题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun defaultRowIndex(q: QuestionConfigDraft): Int? =
    if (q.entryType == QuestionEntryType.MATRIX) 0 else null

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QuestionChips(questions: List<QuestionConfigDraft>, selected: QuestionConfigDraft, onSelect: (QuestionConfigDraft) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        questions.forEach { q ->
            FilterChip(
                selected = q.num == selected.num,
                onClick = { onSelect(q) },
                label = { Text("${q.num}题", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RowChips(q: QuestionConfigDraft, selected: Int?, onSelect: (Int?) -> Unit) {
    if (q.entryType != QuestionEntryType.MATRIX) return
    val rows = q.rowTexts.ifEmpty { List(maxOf(1, q.matrixRowWeights.size)) { "第${it + 1}行" } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("矩阵行", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEachIndexed { i, row ->
                FilterChip(
                    selected = selected == i,
                    onClick = { onSelect(i) },
                    label = { Text(row.ifEmpty { "第${i + 1}行" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun OptionChips(q: QuestionConfigDraft, selected: androidx.compose.runtime.snapshots.SnapshotStateList<Int>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        q.optionTexts.forEachIndexed { i, opt ->
            FilterChip(
                selected = i in selected,
                onClick = { if (i in selected) selected.remove(i) else selected.add(i) },
                label = { Text(opt.ifEmpty { "选项${i + 1}" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun RuleCard(rule: Map<String, Any?>, questions: List<QuestionConfigDraft>, onDelete: () -> Unit) {
    fun qTitle(num: Any?): String {
        val n = (num as? Number)?.toInt() ?: return "?"
        return questions.firstOrNull { it.num == n }?.let { "${it.num}.${it.title.take(10)}" } ?: "第${n}题"
    }
    fun rowLabel(questionNum: Any?, rowIndex: Any?): String {
        val qNum = (questionNum as? Number)?.toInt() ?: return ""
        val row = (rowIndex as? Number)?.toInt() ?: return ""
        val q = questions.firstOrNull { it.num == qNum && it.entryType == QuestionEntryType.MATRIX } ?: return ""
        val label = q.rowTexts.getOrNull(row)?.takeIf { it.isNotBlank() } ?: "第${row + 1}行"
        return "[$label]"
    }
    @Suppress("UNCHECKED_CAST")
    fun opts(key: String): String = (rule[key] as? List<*>)?.joinToString(",") { ((it as? Number)?.toInt()?.plus(1)).toString() } ?: ""
    val condMode = if (rule["condition_mode"] == "not_selected") "未选" else "选中"
    val actMode = if (rule["action_mode"] == "must_not_select") "禁选" else "必选"
    val condRow = rowLabel(rule["condition_question_num"], rule["condition_row_index"])
    val targetRow = rowLabel(rule["target_question_num"], rule["target_row_index"])
    StudioCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                "当 ${qTitle(rule["condition_question_num"])}$condRow $condMode 第[${opts("condition_option_indices")}]项 → ${qTitle(rule["target_question_num"])}$targetRow $actMode 第[${opts("target_option_indices")}]项",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
        }
    }
}
