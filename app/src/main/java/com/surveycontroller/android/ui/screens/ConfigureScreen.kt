package com.surveycontroller.android.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.model.QuestionEntryType
import com.surveycontroller.android.core.reverse_fill.REVERSE_FILL_STATUS_REVERSE
import com.surveycontroller.android.core.reverse_fill.ReverseFillSpec
import com.surveycontroller.android.core.questions.TextValues
import com.surveycontroller.android.data.ConfigCompiler
import com.surveycontroller.android.data.ConfigPreflight
import com.surveycontroller.android.data.QuestionConfigDraft
import com.surveycontroller.android.data.supportsBulkDimension
import com.surveycontroller.android.provider.SurveyProviderType
import com.surveycontroller.android.ui.MainViewModel
import com.surveycontroller.android.ui.components.StudioCard
import com.surveycontroller.android.ui.components.StudioHeading
import com.surveycontroller.android.ui.components.StudioTopBar
import com.surveycontroller.android.ui.components.HitRateBar
import com.surveycontroller.android.ui.components.LabeledSlider
import com.surveycontroller.android.ui.components.RatioSliders
import com.surveycontroller.android.ui.components.WeightEditor
import com.surveycontroller.android.ui.components.evenSplit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.roundToInt
import com.surveycontroller.android.ui.theme.ChromeDarkColors
import com.surveycontroller.android.ui.theme.LocalChromeDarkTheme
import com.surveycontroller.android.ui.components.studioSuccessColor

private enum class ConfigSection(val label: String) { QUESTIONS("题目"), PARAMETERS("运行参数"), CHECK("检查") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureScreen(viewModel: MainViewModel, onBack: () -> Unit, onRun: () -> Unit, onEditRules: () -> Unit) {
    val state by viewModel.workbench.collectAsState()
    val draft = state.draft
    var section by rememberSaveable { mutableStateOf(ConfigSection.QUESTIONS) }
    var typeFilter by rememberSaveable { mutableStateOf<QuestionEntryType?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var showingRehearsal by remember { mutableStateOf(false) }
    var expandedQuestion by rememberSaveable { mutableStateOf<Int?>(null) }
    var targetQuestion by remember { mutableStateOf<Int?>(null) }
    var jumpRequest by remember { mutableStateOf(0) }
    val questionsList = rememberLazyListState()
    val editableQuestions = draft?.questions?.map { it.num }?.toSet().orEmpty()
    val jumpToQuestion: (Int) -> Unit = { number ->
        if (number in editableQuestions) {
            showingRehearsal = false
            section = ConfigSection.QUESTIONS
            typeFilter = null
            search = ""
            targetQuestion = number
            expandedQuestion = number
            jumpRequest++
        }
    }
    LaunchedEffect(jumpRequest) {
        val index = draft?.questions?.indexOfFirst { it.num == targetQuestion } ?: -1
        if (index >= 0) questionsList.scrollToItem(index + 1)
    }
    if (showingRehearsal) {
        OfflineRehearsalDialog(viewModel, editableQuestions, onDismiss = { showingRehearsal = false }, onEditQuestion = jumpToQuestion)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            StudioTopBar("配置工作区", onBack, draft?.definition?.title?.ifBlank { "未命名问卷" })
        },
        bottomBar = {
            if (draft != null) {
                Surface(
                    color = if (LocalChromeDarkTheme.current) ChromeDarkColors.toolbar else MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 2.dp,
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Row(
                            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().navigationBarsPadding().padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            OutlinedButton(
                                onClick = { showingRehearsal = true },
                                enabled = draft.questions.isNotEmpty(),
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = MaterialTheme.shapes.medium,
                            ) { Text("离线试跑") }
                            Button(
                                onClick = if (section == ConfigSection.CHECK) onRun else { { section = ConfigSection.CHECK } },
                                modifier = Modifier.weight(1.3f).height(48.dp),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                val run = section == ConfigSection.CHECK
                                Icon(if (run) Icons.Filled.PlayArrow else Icons.Filled.Checklist, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (run) "开始执行 · ${draft.params.targetNum} 份" else "检查配置", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
    ) { inner ->
        if (draft == null) {
            Text("请先解析问卷", modifier = Modifier.padding(inner).padding(20.dp))
            return@Scaffold
        }

        Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 5.dp)) {
                    ConfigSection.entries.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = section == tab,
                            onClick = { section = tab },
                            shape = SegmentedButtonDefaults.itemShape(index, ConfigSection.entries.size),
                            label = { Text(tab.label) },
                        )
                    }
                }
                when (section) {
                    ConfigSection.QUESTIONS -> {
                        val query = search.trim()
                        val shown = draft.questions.withIndex().filter { (_, question) ->
                            (typeFilter == null || question.entryType == typeFilter) &&
                                (query.isEmpty() || question.num.toString() == query || question.title.contains(query, ignoreCase = true))
                        }
                        val metaByNum = draft.definition.questions.associateBy { it.num }
                        val suggestions = dimensionSuggestions(draft)
                        LazyColumn(
                            state = questionsList,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    OutlinedTextField(
                                        value = search,
                                        onValueChange = { search = it },
                                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                        label = { Text("搜索题号或题目") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    TypeFilterRow(draft, typeFilter) { typeFilter = it }
                                    OutlinedButton(onClick = onEditRules, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(17.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Text("条件规则 · ${draft.answerRules.size} 条")
                                    }
                                    if (shown.isEmpty()) Text("找不到符合条件的题目", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            itemsIndexed(shown, key = { _, question -> question.index }) { _, indexed ->
                                val question = indexed.value
                                QuestionCard(
                                    question, metaByNum[question.num], suggestions, question.num == targetQuestion,
                                    expanded = question.num == expandedQuestion,
                                    onToggle = {
                                        expandedQuestion = if (expandedQuestion == question.num) null else question.num
                                        targetQuestion = null
                                    },
                                ) { transform -> viewModel.updateQuestion(indexed.index, transform) }
                            }
                        }
                    }
                    ConfigSection.PARAMETERS -> LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { RunParamsCard(viewModel) }
                    }
                    ConfigSection.CHECK -> LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { StudioHeading("配置检查", "检查问卷配置，或先生成离线样例。") }
                        item { PreflightCard(viewModel, editableQuestions, jumpToQuestion) }
                        item { SurveyOverviewCard(draft, viewModel) }
                        item { ConfigIoRow(viewModel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreflightCard(viewModel: MainViewModel, editableQuestions: Set<Int>, onEditQuestion: (Int) -> Unit) {
    val state by viewModel.workbench.collectAsState()
    val settings by viewModel.settingsStore.settings.collectAsState(initial = com.surveycontroller.android.data.SettingsStore.Settings())
    val draft = state.draft ?: return
    val result = remember(
        draft,
        state.reverseFillEnabled,
        state.reverseFillSampleCount,
        state.reverseFillSpec,
        settings.aiEnabled,
        settings.aiBaseUrl,
        settings.aiApiKey,
        settings.aiModel,
        settings.aiApiProtocol,
    ) {
        val localAi = ConfigCompiler.AiOptions(
            enabled = settings.aiEnabled,
            baseUrl = settings.aiBaseUrl,
            apiKey = settings.aiApiKey,
            model = settings.aiModel,
            apiProtocol = settings.aiApiProtocol,
            systemPrompt = settings.aiSystemPrompt,
            submissionReportEnabled = settings.submissionReportTelemetry,
        )
        val (effectiveParams, effectiveAi) = ConfigCompiler.resolveAiOptions(draft, localAi)
        ConfigPreflight.validate(
            draft.copy(params = effectiveParams),
            ConfigPreflight.Options(
                customAiEnabled = effectiveAi.enabled,
                customAiBaseUrl = effectiveAi.baseUrl,
                customAiApiKey = effectiveAi.apiKey,
                customAiModel = effectiveAi.model,
                customAiApiProtocol = effectiveAi.apiProtocol,
                reverseFillEnabled = state.reverseFillEnabled,
                reverseFillSampleCount = state.reverseFillSampleCount,
                reverseFillSpec = state.reverseFillSpec,
            ),
        )
    }
    val externalError = state.error
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless { it.startsWith("启动预检未通过") }
    var expanded by remember { mutableStateOf(false) }
    StudioCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val hasErrors = result.errors.isNotEmpty() || externalError != null
            Text(
                if (hasErrors) "需要处理" else "检查结果",
                style = MaterialTheme.typography.titleMedium,
                color = if (hasErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            externalError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (result.errors.isEmpty() && result.warnings.isEmpty() && externalError == null) {
                Text("检查通过", color = studioSuccessColor())
            }
            val visibleErrors = if (expanded) result.errors else result.errors.take(4)
            val visibleWarnings = if (expanded) result.warnings else result.warnings.take(3)
            if (visibleErrors.isNotEmpty()) Text("必须修复", color = MaterialTheme.colorScheme.error)
            visibleErrors.forEach { PreflightLine(it, true, editableQuestions, onEditQuestion) }
            if (visibleWarnings.isNotEmpty()) Text("建议检查", modifier = Modifier.padding(top = 6.dp))
            visibleWarnings.forEach { PreflightLine(it, false, editableQuestions, onEditQuestion) }
            if (result.errors.size > 4 || result.warnings.size > 3) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "查看全部检查结果")
                }
            }
        }
    }
}

@Composable
private fun PreflightLine(
    issue: ConfigPreflight.Issue,
    isError: Boolean,
    editableQuestions: Set<Int>,
    onEditQuestion: (Int) -> Unit,
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("• ${issue.title}", style = MaterialTheme.typography.bodyMedium, color = color)
        if (issue.detail.isNotBlank()) {
            Text(issue.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        issue.questionNum?.takeIf { it in editableQuestions }?.let { number ->
            TextButton(onClick = { onEditQuestion(number) }) { Text("编辑此题") }
        }
    }
}

@Composable
private fun SurveyOverviewCard(draft: com.surveycontroller.android.data.SurveyConfigDraft, viewModel: MainViewModel) {
    val counts = draft.questions.groupingBy { it.entryType }.eachCount()
    val dimensionCounts = draft.questions
        .mapNotNull { it.dimension?.trim()?.takeIf { value -> value.isNotEmpty() } }
        .groupingBy { it }
        .eachCount()
    val preservedOnlyDimensions = draft.preserved.dimensionGroups.filter { it !in dimensionCounts.keys }
    StudioCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(draft.definition.title.ifEmpty { "未命名问卷" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("共 ${draft.questions.size} 题 · " + counts.entries.joinToString(" / ") { "${entryTypeLabel(it.key)}${it.value}" },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            if (dimensionCounts.isNotEmpty() || preservedOnlyDimensions.isNotEmpty()) {
                Text(
                    buildString {
                        append("维度：")
                        if (dimensionCounts.isNotEmpty()) {
                            append(dimensionCounts.entries.joinToString(" / ") { "${it.key}${it.value}" })
                        }
                        if (preservedOnlyDimensions.isNotEmpty()) {
                            if (dimensionCounts.isNotEmpty()) append(" / ")
                            append("已导入未使用 ${preservedOnlyDimensions.joinToString("、")}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            DimensionBulkRow(draft, viewModel)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.bulkApply(com.surveycontroller.android.ui.BulkMode.EVEN) }, modifier = Modifier.weight(1f)) {
                    Text("全部均分")
                }
                OutlinedButton(onClick = { viewModel.bulkApply(com.surveycontroller.android.ui.BulkMode.RANDOM) }, modifier = Modifier.weight(1f)) {
                    Text("全部随机")
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DimensionBulkRow(draft: com.surveycontroller.android.data.SurveyConfigDraft, viewModel: MainViewModel) {
    val metaByNum = draft.definition.questions.associateBy { it.num }
    val supportedCount = draft.questions.count { it.supportsBulkDimension(metaByNum[it.num]) }
    if (supportedCount <= 0) return
    val suggestions = dimensionSuggestions(draft)
    var customName by remember(draft) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            "维度批量管理：$supportedCount 道信度题可批量分组",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (suggestions.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                suggestions.take(8).forEach { name ->
                    androidx.compose.material3.FilterChip(
                        selected = false,
                        onClick = { viewModel.bulkApplyDimension(name) },
                        label = { Text("应用 $name") },
                    )
                }
                androidx.compose.material3.FilterChip(
                    selected = false,
                    onClick = { viewModel.bulkApplyDimension(null) },
                    label = { Text("清空") },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = customName,
                onValueChange = { customName = it },
                label = { Text("新维度名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    val name = normalizeDimensionName(customName) ?: return@OutlinedButton
                    viewModel.bulkApplyDimension(name)
                    customName = ""
                },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("应用") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TypeFilterRow(draft: com.surveycontroller.android.data.SurveyConfigDraft, selected: QuestionEntryType?, onSelect: (QuestionEntryType?) -> Unit) {
    val types = draft.questions.map { it.entryType }.distinct()
    if (types.size <= 1) return
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("全部") })
        types.forEach { t ->
            androidx.compose.material3.FilterChip(
                selected = selected == t,
                onClick = { onSelect(if (selected == t) null else t) },
                label = { Text(entryTypeLabel(t)) },
            )
        }
    }
}

@Composable
private fun ConfigIoRow(viewModel: MainViewModel) {
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportConfig(uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importConfig(uri)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { exporter.launch("survey_config.json") }, modifier = Modifier.weight(1f)) {
            Text("导出配置")
        }
        OutlinedButton(
            onClick = { importer.launch(arrayOf("application/json", "application/octet-stream")) },
            modifier = Modifier.weight(1f),
        ) { Text("导入配置") }
    }
}

@Composable
private fun RunParamsCard(viewModel: MainViewModel) {
    val state by viewModel.workbench.collectAsState()
    val draft = state.draft ?: return
    val params = draft.params
    StudioCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("运行设置", style = MaterialTheme.typography.titleMedium)
            com.surveycontroller.android.ui.components.Stepper(
                label = "目标份数",
                value = params.targetNum,
                onValueChange = { v -> viewModel.updateParams { it.copy(targetNum = v) } },
                range = 1..9999,
                step = 1,
                suffix = "份",
            )
            com.surveycontroller.android.ui.components.Stepper(
                label = "并发数",
                value = params.numThreads,
                onValueChange = { v -> viewModel.updateParams { it.copy(numThreads = v) } },
                range = 1..16,
                step = 1,
                helper = "同时进行的填写线程数",
            )
            com.surveycontroller.android.ui.components.Stepper(
                label = "单份作答时长下限",
                value = params.answerDurationMin,
                onValueChange = { v -> viewModel.updateParams { it.copy(answerDurationMin = v.coerceAtMost(it.answerDurationMax)) } },
                range = 1..600,
                step = 5,
                suffix = "秒",
            )
            com.surveycontroller.android.ui.components.Stepper(
                label = "单份作答时长上限",
                value = params.answerDurationMax,
                onValueChange = { v -> viewModel.updateParams { it.copy(answerDurationMax = v.coerceAtLeast(it.answerDurationMin)) } },
                range = 1..600,
                step = 5,
                suffix = "秒",
                helper = "每份在下限~上限之间随机取，模拟真人",
            )
            com.surveycontroller.android.ui.components.Stepper(
                label = "提交间隔下限",
                value = params.submitIntervalMin,
                onValueChange = { v -> viewModel.updateParams { it.copy(submitIntervalMin = v.coerceAtMost(it.submitIntervalMax)) } },
                range = 0..300,
                step = 5,
                suffix = "秒",
            )
            com.surveycontroller.android.ui.components.Stepper(
                label = "提交间隔上限",
                value = params.submitIntervalMax,
                onValueChange = { v -> viewModel.updateParams { it.copy(submitIntervalMax = v.coerceAtLeast(it.submitIntervalMin)) } },
                range = 0..300,
                step = 5,
                suffix = "秒",
                helper = "两次成功/失败尝试之间的等待时间范围",
            )
            if (draft.definition.provider.id == "credamo") {
                AnswerDatetimeWindowEditor(viewModel)
            }
            ToggleRow("随机 IP（需代理资源）", params.randomProxyIpEnabled) { on ->
                viewModel.updateParams { it.copy(randomProxyIpEnabled = on) }
            }
            if (params.randomProxyIpEnabled) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = params.proxySource == "default",
                        onClick = { viewModel.updateParams { it.copy(proxySource = "default") } },
                        label = { Text("默认源") },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = params.proxySource == "benefit",
                        onClick = { viewModel.updateParams { it.copy(proxySource = "benefit") } },
                        label = { Text("限时福利") },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = params.proxySource == "custom",
                        onClick = { viewModel.updateParams { it.copy(proxySource = "custom") } },
                        label = { Text("自定义") },
                    )
                }
                if (params.proxySource == "custom") {
                    OutlinedTextField(
                        value = params.customProxyApi,
                        onValueChange = { v -> viewModel.updateParams { it.copy(customProxyApi = v) } },
                        label = { Text("代理 API 地址（可含 {num}/{area}）") },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = params.proxyAreaCode ?: "",
                    onValueChange = { v -> viewModel.updateParams { it.copy(proxyAreaCode = v.ifBlank { null }) } },
                    label = { Text("指定地区编码（可选，如 110100=北京）") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    singleLine = true,
                )
            }
            ToggleRow("随机 User-Agent", params.randomUserAgentEnabled) { on ->
                viewModel.updateParams { it.copy(randomUserAgentEnabled = on) }
            }
            if (params.randomUserAgentEnabled) {
                UserAgentRatioEditor(viewModel)
            }
            ToggleRow("失败达阈值自动停止", params.stopOnFailEnabled) { on ->
                viewModel.updateParams { it.copy(stopOnFailEnabled = on) }
            }
            if (params.stopOnFailEnabled) {
                com.surveycontroller.android.ui.components.Stepper(
                    label = "连续失败阈值",
                    value = params.failThreshold,
                    onValueChange = { v -> viewModel.updateParams { it.copy(failThreshold = v.coerceAtLeast(1)) } },
                    range = 1..100,
                    step = 1,
                    suffix = "次",
                )
            }
            ToggleRow("信度模式（同维度题一致性优化）", params.reliabilityModeEnabled) { on ->
                viewModel.updateParams { it.copy(reliabilityModeEnabled = on) }
            }
            if (params.reliabilityModeEnabled) {
                OutlinedTextField(
                    value = params.psychoTargetAlpha.toString(),
                    onValueChange = { raw ->
                        val value = raw.toDoubleOrNull()
                        if (value != null) viewModel.updateParams { it.copy(psychoTargetAlpha = value.coerceIn(0.60, 0.95)) }
                    },
                    label = { Text("目标 Cronbach's α（0.60-0.95）") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
            }
            ToggleRow("命中验证码时暂停任务", params.pauseOnAliyunCaptcha) { on ->
                viewModel.updateParams { it.copy(pauseOnAliyunCaptcha = on) }
            }
            ReverseFillRow(viewModel)
        }
    }
}

@Composable
private fun AnswerDatetimeWindowEditor(viewModel: MainViewModel) {
    val params = viewModel.workbench.collectAsState().value.draft?.params ?: return
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("见数提交时间窗", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("格式：2026-06-07 14:30；留空则使用当前提交时间。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = params.answerDatetimeStart,
                onValueChange = { v -> viewModel.updateParams { it.copy(answerDatetimeStart = v) } },
                label = { Text("开始") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = params.answerDatetimeEnd,
                onValueChange = { v -> viewModel.updateParams { it.copy(answerDatetimeEnd = v) } },
                label = { Text("结束") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
    }
}

@Composable
private fun UserAgentRatioEditor(viewModel: MainViewModel) {
    val params = viewModel.workbench.collectAsState().value.draft?.params ?: return
    val keys = listOf("wechat", "mobile", "pc")
    val labels = listOf("微信访问", "手机浏览器", "电脑链接")
    val values = keys.map { params.randomUserAgentRatios[it] ?: 0 }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("User-Agent 设备占比", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RatioSliders(labels = labels, values = values, onChange = { next ->
            viewModel.updateParams { it.copy(randomUserAgentRatios = keys.zip(next).toMap()) }
        })
    }
}

@Composable
private fun ReverseFillRow(viewModel: MainViewModel) {
    val state by viewModel.workbench.collectAsState()
    val context = LocalContext.current
    val draft = state.draft ?: return
    val preserved = draft.preserved
    val reverseFillSupported = draft.definition.provider == SurveyProviderType.WJX
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "导入文件"
            viewModel.loadReverseFill(uri, name)
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("反向填充（按已有数据回放）", style = MaterialTheme.typography.bodyMedium)
            if (reverseFillSupported && state.reverseFillLoaded && state.reverseFillSampleCount > 0) {
                Switch(checked = state.reverseFillEnabled, onCheckedChange = viewModel::setReverseFillEnabled)
            }
        }
        if (!reverseFillSupported) {
            Text(
                "反向填充当前仅支持问卷星导出的 xlsx；${providerName(draft.definition.provider)}请使用常规配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@Column
        }
        ReverseFillFormatRow(preserved.reverseFillFormat, viewModel::updateReverseFillFormat)
        com.surveycontroller.android.ui.components.Stepper(
            label = "起始数据行",
            value = preserved.reverseFillStartRow,
            onValueChange = viewModel::updateReverseFillStartRow,
            range = 1..9999,
            step = 1,
            helper = "第 1 行通常是表头；桌面端同名设置会被保留",
        )
        com.surveycontroller.android.ui.components.Stepper(
            label = "反填并发",
            value = preserved.reverseFillThreads.coerceIn(1, 16),
            onValueChange = viewModel::updateReverseFillThreads,
            range = 1..16,
            step = 1,
            helper = "启用反向填充时会同步为任务并发数",
        )
        if (!state.reverseFillLoaded && preserved.reverseFillEnabled && preserved.reverseFillSourcePath.isNotBlank()) {
            Text(
                "已导入桌面文件：${state.reverseFillFileName ?: preserved.reverseFillSourcePath}，需在手机上重新选择 xlsx 后启用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        OutlinedButton(
            onClick = {
                picker.launch(
                    arrayOf(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/octet-stream",
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(
                when {
                    state.reverseFillLoaded -> state.reverseFillFileName?.let { "$it（${state.reverseFillSampleCount} 份样本）" } ?: "已加载 ${state.reverseFillSampleCount} 份样本"
                    state.reverseFillFileName != null -> "重新选择 xlsx 数据文件"
                    else -> "选择 xlsx 数据文件"
                },
            )
        }
        ReverseFillPreview(state.reverseFillSpec)
    }
}

@Composable
private fun ReverseFillPreview(spec: ReverseFillSpec?) {
    if (spec == null) return
    val planCount = spec.questionPlans.size
    val headerMatched = spec.questionPlans.count { it.matchedByHeader }
    val fallbackMatched = planCount - headerMatched
    val degraded = spec.questionPlans.count { it.status != REVERSE_FILL_STATUS_REVERSE }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            "识别结果：${spec.totalSamples} 份样本 · $planCount 道题 · 表头匹配 $headerMatched · 顺序兜底 $fallbackMatched · 降级 $degraded",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        spec.previewRows.take(3).forEach { row ->
            Text(
                "样本 ${row.rowNumber}：识别 ${row.answeredQuestions}/${row.totalQuestions} 题",
                style = MaterialTheme.typography.bodySmall,
                color = if (row.answeredQuestions < row.totalQuestions) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        spec.questionPlans.take(4).forEach { plan ->
            val headers = plan.columnHeaders.filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "列 ${plan.columnIndexes.joinToString(",") { (it + 1).toString() }}" }
            val statusText = when {
                plan.status != REVERSE_FILL_STATUS_REVERSE -> "（常规配置）"
                !plan.matchedByHeader -> "（顺序兜底）"
                else -> ""
            }
            Text(
                "第 ${plan.questionNum} 题：$headers$statusText",
                style = MaterialTheme.typography.bodySmall,
                color = if (plan.status != REVERSE_FILL_STATUS_REVERSE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (plan.status != REVERSE_FILL_STATUS_REVERSE && plan.detail.isNotBlank()) {
                Text(
                    plan.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (spec.questionPlans.size > 4) {
            Text("另有 ${spec.questionPlans.size - 4} 道题已匹配", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReverseFillFormatRow(current: String, onSelect: (String) -> Unit) {
    val formats = listOf(
        "auto" to "自动",
        "wjx_text" to "文本",
        "wjx_sequence" to "序号",
        "wjx_score" to "分值",
    )
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("反填识别格式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            formats.forEach { (value, label) ->
                androidx.compose.material3.FilterChip(
                    selected = current == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun QuestionCard(
    q: QuestionConfigDraft,
    meta: SurveyQuestionMeta?,
    dimensionSuggestions: List<String>,
    highlighted: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    update: ((QuestionConfigDraft) -> QuestionConfigDraft) -> Unit,
) {
    StudioCard(
        Modifier.fillMaxWidth(),
        border = BorderStroke(
            1.dp,
            if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Text(q.num.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(q.title.ifBlank { "未命名题目" }, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(entryTypeLabel(q.entryType), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起第 ${q.num} 题" else "展开第 ${q.num} 题")
        }
        if (!expanded) return@StudioCard
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
            QuestionStatusBadges(q, meta)
            QuestionMediaPreview(meta)

            when (q.entryType) {
                QuestionEntryType.SINGLE, QuestionEntryType.DROPDOWN,
                QuestionEntryType.SCALE, QuestionEntryType.SCORE -> {
                    DistributionModeRow(q.distributionMode) { mode -> update { it.copy(distributionMode = mode) } }
                    if (q.distributionMode == "custom" || q.distributionMode == "weighted") {
                        BiasPresetRow(q.biasPreset) { preset ->
                            update { d ->
                                val raw = com.surveycontroller.android.core.questions.TendencyWeights
                                    .buildBiasWeights(d.optionTexts.size, preset).map { it.roundToInt() }
                                val pct = com.surveycontroller.android.ui.components.normalizeTo100(raw)
                                d.copy(biasPreset = preset, optionWeights = pct.map { it.toDouble() }.toMutableList())
                            }
                        }
                        com.surveycontroller.android.ui.components.WeightEditor(
                            labels = q.optionTexts,
                            weights = q.optionWeights.map { it.roundToInt() },
                            onChange = { newValues ->
                                update { d -> d.copy(optionWeights = newValues.map { it.toDouble() }.toMutableList(), biasPreset = "custom") }
                            },
                        )
                    } else {
                        Text("所有选项等概率随机作答", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (q.entryType == QuestionEntryType.SINGLE || q.entryType == QuestionEntryType.DROPDOWN) {
                        OptionFillEditor(q, update)
                    }
                    if (q.entryType == QuestionEntryType.SINGLE) {
                        AttachedSelectEditor(q, update)
                    }
                }
                QuestionEntryType.MULTIPLE -> {
                    ToggleRow("随机数量（忽略各项概率）", q.multiRandomCount) { on -> update { it.copy(multiRandomCount = on) } }
                    if (!q.multiRandomCount) {
                        Text("各选项命中概率（独立，0–100%）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HitRateBar(q.multiProbabilities.map { it.roundToInt() })
                        q.optionTexts.forEachIndexed { i, opt ->
                            val prob = q.multiProbabilities.getOrElse(i) { 50.0 }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(opt.ifEmpty { "选项 ${i + 1}" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                androidx.compose.material3.Slider(
                                    value = prob.toFloat(),
                                    onValueChange = { v -> update { d -> d.copy(multiProbabilities = d.multiProbabilities.toMutableList().also { if (i in it.indices) it[i] = v.toDouble() }) } },
                                    valueRange = 0f..100f,
                                    modifier = Modifier.weight(2f),
                                )
                                OutlinedTextField(
                                    value = prob.roundToInt().toString(),
                                    onValueChange = { s ->
                                        val v = s.filter { it.isDigit() }.take(3).toIntOrNull()?.coerceIn(0, 100) ?: 0
                                        update { d -> d.copy(multiProbabilities = d.multiProbabilities.toMutableList().also { if (i in it.indices) it[i] = v.toDouble() }) }
                                    },
                                    singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                    suffix = { Text("%") },
                                    modifier = Modifier.width(88.dp).padding(start = 8.dp),
                                )
                            }
                        }
                    }
                    OptionFillEditor(q, update)
                    AttachedSelectEditor(q, update)
                }
                QuestionEntryType.MATRIX -> {
                    DistributionModeRow(q.distributionMode) { mode -> update { it.copy(distributionMode = mode) } }
                    if (q.distributionMode == "custom" || q.distributionMode == "weighted") {
                        q.rowTexts.forEachIndexed { r, rowLabel ->
                            Text(rowLabel.ifEmpty { "第 ${r + 1} 行" }, style = MaterialTheme.typography.labelMedium)
                            BiasPresetRow(q.matrixBiasPresets.getOrNull(r) ?: q.biasPreset) { preset ->
                                update { d ->
                                    val raw = com.surveycontroller.android.core.questions.TendencyWeights
                                        .buildBiasWeights(d.optionTexts.size, preset).map { it.roundToInt() }
                                    val pct = com.surveycontroller.android.ui.components.normalizeTo100(raw)
                                    val rows = d.matrixRowWeights.map { it.toMutableList() }.toMutableList()
                                    if (r in rows.indices) rows[r] = pct.map { it.toDouble() }.toMutableList()
                                    val biases = d.matrixBiasPresets.toMutableList()
                                    while (biases.size < maxOf(d.matrixRowWeights.size, d.rowTexts.size, r + 1)) biases.add("custom")
                                    biases[r] = preset
                                    d.copy(matrixRowWeights = rows, matrixBiasPresets = biases)
                                }
                            }
                            com.surveycontroller.android.ui.components.WeightEditor(
                                labels = q.optionTexts,
                                weights = (q.matrixRowWeights.getOrNull(r) ?: emptyList()).map { it.roundToInt() },
                                onChange = { newValues ->
                                    update { d ->
                                        val rows = d.matrixRowWeights.map { it.toMutableList() }.toMutableList()
                                        if (r in rows.indices) rows[r] = newValues.map { it.toDouble() }.toMutableList()
                                        val biases = d.matrixBiasPresets.toMutableList()
                                        while (biases.size < maxOf(d.matrixRowWeights.size, d.rowTexts.size, r + 1)) biases.add("custom")
                                        biases[r] = "custom"
                                        d.copy(matrixRowWeights = rows, matrixBiasPresets = biases)
                                    }
                                },
                                previewPrefix = "本行目标占比：",
                            )
                        }
                    } else {
                        Text("每行所有选项等概率随机作答", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                QuestionEntryType.TEXT, QuestionEntryType.MULTI_TEXT -> {
                    TextAnswerEditor(q, update)
                }
                QuestionEntryType.SLIDER -> {
                    val minValue = q.sliderMin?.toFloat() ?: 0f
                    val maxValue = q.sliderMax?.toFloat()?.takeIf { it > minValue } ?: 100f
                    LabeledSlider(
                        label = "滑块目标值",
                        value = q.sliderTarget.toFloat().coerceIn(minValue, maxValue),
                        onValueChange = { v -> update { it.copy(sliderTarget = v.toDouble()) } },
                        valueRange = minValue..maxValue,
                        valueText = q.sliderTarget.roundToInt().toString(),
                    )
                }
                QuestionEntryType.ORDER -> Text("排序题：将随机生成顺序", style = MaterialTheme.typography.bodySmall)
                QuestionEntryType.LOCATION -> LocationEditor(q, update)
            }

            // 信度维度分组（同名维度的量表题保持作答一致性）
            if (q.entryType in dimensionEditableTypes || q.supportsBulkDimension(meta)) {
                DimensionEditor(q, dimensionSuggestions, update)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DimensionEditor(
    q: QuestionConfigDraft,
    suggestions: List<String>,
    update: ((QuestionConfigDraft) -> QuestionConfigDraft) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        OutlinedTextField(
            value = q.dimension ?: "",
            onValueChange = { v -> update { it.copy(dimension = normalizeDimensionName(v)) } },
            label = { Text("维度分组（选填，同名题一致）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (suggestions.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                suggestions.take(8).forEach { name ->
                    androidx.compose.material3.FilterChip(
                        selected = q.dimension == name,
                        onClick = { update { it.copy(dimension = name) } },
                        label = { Text(name) },
                    )
                }
                androidx.compose.material3.FilterChip(
                    selected = q.dimension.isNullOrBlank(),
                    onClick = { update { it.copy(dimension = null) } },
                    label = { Text("未分组") },
                )
            }
        }
    }
}

@Composable
private fun AttachedSelectEditor(q: QuestionConfigDraft, update: ((QuestionConfigDraft) -> QuestionConfigDraft) -> Unit) {
    val configs = q.attachedOptionSelects
    if (configs.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("嵌入式下拉", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        configs.forEachIndexed { configIndex, config ->
            val optionIndex = config["option_index"].asIntOrNull()
            val optionLabel = config["option_text"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: optionIndex?.let { q.optionTexts.getOrNull(it) }
                ?: "主选项 ${configIndex + 1}"
            val selectOptions = config["select_options"].asStringList()
                .ifEmpty { List(config["select_option_count"].asIntOrNull()?.coerceAtLeast(0) ?: 0) { "子选项 ${it + 1}" } }
            if (selectOptions.isEmpty()) return@forEachIndexed
            val weights = config["weights"].asDoubleList()
                .map { it.roundToInt() }
                .takeIf { it.size == selectOptions.size && it.any { value -> value > 0 } }
                ?: evenSplit(selectOptions.size)
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(optionLabel, style = MaterialTheme.typography.bodyMedium)
                WeightEditor(
                    labels = selectOptions,
                    weights = weights,
                    onChange = { next ->
                        update { draft ->
                            draft.copy(attachedOptionSelects = updateAttachedSelectWeights(draft.attachedOptionSelects, configIndex, next))
                        }
                    },
                )
            }
        }
    }
}

internal fun updateAttachedSelectWeights(
    configs: List<Map<String, Any?>>,
    configIndex: Int,
    weights: List<Int>,
): List<Map<String, Any?>> =
    configs.mapIndexed { idx, item ->
        if (idx == configIndex) item + ("weights" to weights.map { it.toDouble() }) else item
    }

@Composable
private fun QuestionMediaPreview(meta: SurveyQuestionMeta?) {
    val media = meta?.questionMedia?.takeIf { it.isNotEmpty() } ?: return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("图片附件", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        media.take(3).forEach { item ->
            val scopeLabel = when (item.scope) {
                "title" -> "题干"
                "option" -> item.index?.let { "选项 ${it + 1}" } ?: "选项"
                "row" -> item.index?.let { "行 ${it + 1}" } ?: "矩阵行"
                else -> item.scope.ifBlank { "图片" }
            }
            QuestionMediaRow(scopeLabel, item.label.ifBlank { item.sourceUrl }, item.sourceUrl)
        }
        if (media.size > 3) {
            Text("另有 ${media.size - 3} 张图片", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuestionMediaRow(scopeLabel: String, label: String, sourceUrl: String) {
    val bitmap by produceState<Bitmap?>(initialValue = null, sourceUrl) {
        value = withContext(Dispatchers.IO) { loadPreviewBitmap(sourceUrl) }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).padding(end = 8.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(scopeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun loadPreviewBitmap(sourceUrl: String): Bitmap? {
    val text = sourceUrl.trim()
    if (!text.startsWith("http://") && !text.startsWith("https://")) return null
    return runCatching {
        val connection = URL(text).openConnection().apply {
            connectTimeout = 2500
            readTimeout = 2500
        }
        connection.getInputStream().use { stream ->
            BitmapFactory.decodeStream(stream)
        }
    }.getOrNull()
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QuestionStatusBadges(q: QuestionConfigDraft, meta: SurveyQuestionMeta?) {
    if (meta == null && q.fillableOptionIndices.isEmpty()) return
    val badges = mutableListOf<Pair<String, Boolean>>()
    if (meta?.unsupported == true) badges.add("暂不支持" to true)
    if (meta?.required == true) badges.add("必答" to false)
    if (meta?.questionMedia?.isNotEmpty() == true) badges.add("图片 ${meta.questionMedia.size}" to false)
    if (meta?.hasJump == true) badges.add("跳题" to false)
    if (meta?.hasDisplayCondition == true) badges.add("条件显示" to false)
    if (meta?.hasDependentDisplayLogic == true) badges.add("控制后续" to false)
    if (q.fillableOptionIndices.isNotEmpty()) badges.add("选项填空" to false)
    if (meta?.forcedOptionIndex != null || meta?.forcedTexts?.isNotEmpty() == true) badges.add("强制项" to false)
    if (meta?.hasAttachedOptionSelect == true) badges.add("嵌入下拉" to false)
    if (badges.isEmpty()) return

    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        badges.forEach { (label, isError) ->
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
    val unsupportedReason = meta?.unsupportedReason?.takeIf { it.isNotBlank() }
    if (meta?.unsupported == true && unsupportedReason != null) {
        Text(
            unsupportedReason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun OptionFillEditor(q: QuestionConfigDraft, update: ((QuestionConfigDraft) -> QuestionConfigDraft) -> Unit) {
    val indices = q.fillableOptionIndices.filter { it in q.optionTexts.indices }
    if (indices.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("选项填空（如“其他____”）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        indices.forEach { optionIndex ->
            OutlinedTextField(
                value = q.optionFillCandidates[optionIndex]?.joinToString("\n")
                    ?: q.optionFillTexts.getOrNull(optionIndex).orEmpty(),
                onValueChange = { value ->
                    update { draft ->
                        val fills = MutableList(maxOf(draft.optionTexts.size, draft.optionFillTexts.size)) { idx ->
                            draft.optionFillTexts.getOrNull(idx)
                        }
                        val lines = value.lines()
                        fills[optionIndex] = lines.singleOrNull()
                        val candidates = if (lines.size > 1) {
                            draft.optionFillCandidates + (optionIndex to lines)
                        } else {
                            draft.optionFillCandidates - optionIndex
                        }
                        draft.copy(optionFillTexts = fills, optionFillCandidates = candidates)
                    }
                },
                label = { Text("${q.optionTexts[optionIndex].ifBlank { "选项 ${optionIndex + 1}" }} 的补充文本") },
                supportingText = { Text("每行一个候选，作答时随机抽取") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun LocationEditor(q: QuestionConfigDraft, update: ((QuestionConfigDraft) -> QuestionConfigDraft) -> Unit) {
    fun locationValue(index: Int): String = q.locationParts.getOrNull(index).orEmpty()
    fun updateLocation(index: Int, value: String) {
        update { draft ->
            val parts = MutableList(3) { i -> draft.locationParts.getOrNull(i).orEmpty() }
            parts[index] = value
            draft.copy(locationParts = parts)
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("定位题地区（省 / 市 / 区县）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("请填写完整三级地区；全部留空时运行时使用默认地区兜底。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = locationValue(0),
                onValueChange = { updateLocation(0, it) },
                label = { Text("省份") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = locationValue(1),
                onValueChange = { updateLocation(1, it) },
                label = { Text("城市") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = locationValue(2),
                onValueChange = { updateLocation(2, it) },
                label = { Text("区县") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

/** 配比模式切换：完全随机 / 按权重随机 / 自定义配比（自定义=运行时严格收敛到设定比例）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DistributionModeRow(mode: String, onSelect: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.material3.FilterChip(
            selected = mode == "random",
            onClick = { onSelect("random") },
            label = { Text("完全随机") },
        )
        androidx.compose.material3.FilterChip(
            selected = mode == "weighted",
            onClick = { onSelect("weighted") },
            label = { Text("按权重随机") },
        )
        androidx.compose.material3.FilterChip(
            selected = mode == "custom",
            onClick = { onSelect("custom") },
            label = { Text("自定义配比") },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BiasPresetRow(current: String, onSelect: (String) -> Unit) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text("倾向预设（一键生成配比）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.surveycontroller.android.core.questions.TendencyWeights.PRESETS.forEach { (value, label) ->
                androidx.compose.material3.FilterChip(
                    selected = current == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
            androidx.compose.material3.FilterChip(
                selected = current == "custom",
                onClick = { },
                enabled = current == "custom",
                label = { Text("自定义") },
            )
        }
    }
}

private val TEXT_MODES = listOf(
    "custom" to "自定义",
    "name" to "随机姓名",
    "mobile" to "随机手机号",
    "id_card" to "随机身份证",
    "integer" to "随机整数",
    "generic" to "随机文本",
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TextAnswerEditor(q: QuestionConfigDraft, update: ((QuestionConfigDraft) -> QuestionConfigDraft) -> Unit) {
    Column {
        Text("填空答案模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TEXT_MODES.forEach { (value, label) ->
                androidx.compose.material3.FilterChip(
                    selected = q.textMode == value && !q.useAiText,
                    onClick = { update { it.copy(textMode = value, useAiText = false) } },
                    label = { Text(label) },
                )
            }
            androidx.compose.material3.FilterChip(
                selected = q.useAiText,
                onClick = { update { it.copy(useAiText = !it.useAiText) } },
                label = { Text("AI 生成") },
            )
        }
        when {
            q.useAiText -> Text("将调用 AI 根据题目内容自动生成填空答案", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            q.textMode == "custom" && q.entryType == QuestionEntryType.MULTI_TEXT -> {
                MultiTextCandidateEditor(q, update)
            }
            q.textMode == "custom" -> {
                q.textCandidates.forEachIndexed { i, cand ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = cand,
                            onValueChange = { v -> update { d -> d.copy(textCandidates = d.textCandidates.toMutableList().also { it[i] = v }) } },
                            label = { Text("候选答案 ${i + 1}") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        if (q.textCandidates.size > 1) {
                            IconButton(onClick = { update { d -> d.copy(textCandidates = d.textCandidates.toMutableList().also { it.removeAt(i) }) } }) {
                                Icon(Icons.Filled.Close, contentDescription = "删除")
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { update { d -> d.copy(textCandidates = (d.textCandidates + "").toMutableList()) } },
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("+ 添加候选答案（随机选用）") }
            }
            q.textMode == "integer" -> {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = q.textIntMin.toString(),
                        onValueChange = { v -> update { it.copy(textIntMin = v.toIntOrNull() ?: 0) } },
                        label = { Text("最小值") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = q.textIntMax.toString(),
                        onValueChange = { v -> update { it.copy(textIntMax = v.toIntOrNull() ?: 0) } },
                        label = { Text("最大值") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
            }
            else -> {
                val hint = TEXT_MODES.firstOrNull { it.first == q.textMode }?.second ?: ""
                Text("每份将自动生成不同的「$hint」", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (q.entryType == QuestionEntryType.MULTI_TEXT && !q.useAiText) {
            MultiTextBlankEditor(q, update)
        }
    }
}

@Composable
private fun MultiTextCandidateEditor(q: QuestionConfigDraft, update: ((QuestionConfigDraft) -> QuestionConfigDraft) -> Unit) {
    val blankCount = maxOf(1, q.multiTextBlankModes.size, q.textInputLabels.size, q.textInputsFallback())
    val rows = decodeMultiTextRows(q.textCandidates, blankCount)

    fun commit(nextRows: List<List<String>>) {
        val encoded = nextRows.map { row -> row.take(blankCount).joinToString(TextValues.MULTI_TEXT_DELIMITER) }.toMutableList()
        update { it.copy(textCandidates = encoded.ifEmpty { mutableListOf("") }) }
    }

    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("候选答案组（每行对应多个空）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEachIndexed { rowIndex, row ->
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("答案组 ${rowIndex + 1}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (rows.size > 1) {
                        IconButton(onClick = { commit(rows.toMutableList().also { it.removeAt(rowIndex) }) }) {
                            Icon(Icons.Filled.Close, contentDescription = "删除")
                        }
                    }
                }
                repeat(blankCount) { blankIndex ->
                    OutlinedTextField(
                        value = row.getOrNull(blankIndex).orEmpty(),
                        onValueChange = { value ->
                            val next = rows.map { it.toMutableList() }.toMutableList()
                            next[rowIndex][blankIndex] = value
                            commit(next)
                        },
                        label = { Text(q.textInputLabels.getOrNull(blankIndex)?.takeIf { it.isNotBlank() } ?: "填空 ${blankIndex + 1}") },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        singleLine = true,
                    )
                }
            }
        }
        OutlinedButton(
            onClick = { commit(rows + listOf(List(blankCount) { "" })) },
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("+ 添加答案组") }
    }
}

private fun QuestionConfigDraft.textInputsFallback(): Int =
    textCandidates.firstOrNull()?.let { TextValues.splitMultiTextCandidate(it).size } ?: 1

private fun decodeMultiTextRows(candidates: List<String>, blankCount: Int): List<List<String>> {
    val source = candidates.ifEmpty { listOf("") }
    return source.map { candidate ->
        val parts = TextValues.splitMultiTextCandidate(candidate).toMutableList()
        while (parts.size < blankCount) parts.add("")
        parts.take(blankCount)
    }.ifEmpty { listOf(List(blankCount) { "" }) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MultiTextBlankEditor(q: QuestionConfigDraft, update: ((QuestionConfigDraft) -> QuestionConfigDraft) -> Unit) {
    val blankCount = maxOf(1, q.multiTextBlankModes.size, q.multiTextBlankAiFlags.size, q.textInputLabels.size)
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("每个空独立配置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        repeat(blankCount) { blankIndex ->
            val mode = q.multiTextBlankModes.getOrNull(blankIndex) ?: "custom"
            val aiEnabled = q.multiTextBlankAiFlags.getOrNull(blankIndex) == true
            Text(
                q.textInputLabels.getOrNull(blankIndex)?.takeIf { it.isNotBlank() } ?: "第 ${blankIndex + 1} 个空",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.FilterChip(
                    selected = aiEnabled,
                    onClick = {
                        update { draft ->
                            val flags = draft.multiTextBlankAiFlags.toMutableList()
                            while (flags.size < blankCount) flags.add(false)
                            flags[blankIndex] = !aiEnabled
                            draft.copy(multiTextBlankAiFlags = flags)
                        }
                    },
                    label = { Text("AI 生成") },
                )
                MULTI_TEXT_BLANK_MODES.forEach { (value, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = !aiEnabled && mode == value,
                        enabled = !aiEnabled,
                        onClick = {
                            update { draft ->
                                val modes = draft.multiTextBlankModes.toMutableList()
                                val flags = draft.multiTextBlankAiFlags.toMutableList()
                                while (modes.size < blankCount) modes.add("custom")
                                while (flags.size < blankCount) flags.add(false)
                                modes[blankIndex] = value
                                flags[blankIndex] = false
                                draft.copy(multiTextBlankModes = modes, multiTextBlankAiFlags = flags)
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
            if (aiEnabled) {
                Text("该空将调用 AI 单独生成；与随机模式互斥。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!aiEnabled && mode == "integer") {
                val range = q.multiTextBlankIntRanges.getOrNull(blankIndex) ?: mutableListOf(0, 100)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = (range.getOrNull(0) ?: 0).toString(),
                        onValueChange = { value ->
                            update { draft ->
                                val ranges = draft.multiTextBlankIntRanges.map { it.toMutableList() }.toMutableList()
                                while (ranges.size < blankCount) ranges.add(mutableListOf(0, 100))
                                val current = ranges[blankIndex]
                                ranges[blankIndex] = mutableListOf(value.toIntOrNull() ?: 0, current.getOrNull(1) ?: 100)
                                draft.copy(multiTextBlankIntRanges = ranges)
                            }
                        },
                        label = { Text("最小值") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = (range.getOrNull(1) ?: 100).toString(),
                        onValueChange = { value ->
                            update { draft ->
                                val ranges = draft.multiTextBlankIntRanges.map { it.toMutableList() }.toMutableList()
                                while (ranges.size < blankCount) ranges.add(mutableListOf(0, 100))
                                val current = ranges[blankIndex]
                                ranges[blankIndex] = mutableListOf(current.getOrNull(0) ?: 0, value.toIntOrNull() ?: 100)
                                draft.copy(multiTextBlankIntRanges = ranges)
                            }
                        },
                        label = { Text("最大值") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private val MULTI_TEXT_BLANK_MODES = listOf(
    "custom" to "跟随候选",
    "name" to "随机姓名",
    "mobile" to "随机手机号",
    "id_card" to "随机身份证",
    "integer" to "随机整数",
)

private fun entryTypeLabel(type: QuestionEntryType): String = when (type) {
    QuestionEntryType.SINGLE -> "单选题"
    QuestionEntryType.MULTIPLE -> "多选题"
    QuestionEntryType.DROPDOWN -> "下拉题"
    QuestionEntryType.SCALE -> "量表题"
    QuestionEntryType.SCORE -> "评价题"
    QuestionEntryType.MATRIX -> "矩阵题"
    QuestionEntryType.SLIDER -> "滑块题"
    QuestionEntryType.ORDER -> "排序题"
    QuestionEntryType.TEXT -> "填空题"
    QuestionEntryType.MULTI_TEXT -> "多项填空"
    QuestionEntryType.LOCATION -> "定位题"
}

private fun providerName(provider: SurveyProviderType): String = when (provider) {
    SurveyProviderType.WJX -> "问卷星"
    SurveyProviderType.QQ -> "腾讯问卷"
    SurveyProviderType.CREDAMO -> "见数"
}

private val dimensionEditableTypes = setOf(QuestionEntryType.SCALE, QuestionEntryType.SCORE, QuestionEntryType.MATRIX)

private fun Any?.asIntOrNull(): Int? = when (this) {
    is Number -> toInt()
    else -> this?.toString()?.trim()?.toIntOrNull()
}

private fun Any?.asStringList(): List<String> = when (this) {
    is Iterable<*> -> mapNotNull { it?.toString()?.trim()?.takeIf { value -> value.isNotEmpty() } }
    is Array<*> -> mapNotNull { it?.toString()?.trim()?.takeIf { value -> value.isNotEmpty() } }
    else -> emptyList()
}

private fun Any?.asDoubleList(): List<Double> = when (this) {
    is Iterable<*> -> mapNotNull { item ->
        when (item) {
            is Number -> item.toDouble()
            else -> item?.toString()?.trim()?.toDoubleOrNull()
        }
    }
    is Array<*> -> mapNotNull { item ->
        when (item) {
            is Number -> item.toDouble()
            else -> item?.toString()?.trim()?.toDoubleOrNull()
        }
    }
    else -> emptyList()
}

internal fun dimensionSuggestions(draft: com.surveycontroller.android.data.SurveyConfigDraft): List<String> {
    val seen = LinkedHashSet<String>()
    fun add(raw: String?) {
        val normalized = normalizeDimensionName(raw) ?: return
        seen.add(normalized)
    }
    draft.preserved.dimensionGroups.forEach { add(it) }
    draft.questions.forEach { add(it.dimension) }
    return seen.toList()
}

internal fun normalizeDimensionName(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "未分组") return null
    return text
}
