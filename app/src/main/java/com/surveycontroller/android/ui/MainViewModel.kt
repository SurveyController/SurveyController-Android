package com.surveycontroller.android.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.surveycontroller.android.core.backend.BackendClient
import com.surveycontroller.android.core.backend.IpUsageSummary
import com.surveycontroller.android.core.backend.RandomIpSession
import com.surveycontroller.android.core.backend.backendErrorMessage
import com.surveycontroller.android.core.engine.RunEngine
import com.surveycontroller.android.core.engine.RunForegroundService
import com.surveycontroller.android.core.engine.RunProgress
import com.surveycontroller.android.core.engine.RunStatus
import com.surveycontroller.android.core.logging.RunLogArchiver
import com.surveycontroller.android.core.model.QuestionEntryType
import com.surveycontroller.android.core.questions.AnswerRule
import com.surveycontroller.android.core.questions.OfflineRehearsal
import com.surveycontroller.android.core.reverse_fill.ReverseFillBuilder
import com.surveycontroller.android.core.reverse_fill.ReverseFillSpec
import com.surveycontroller.android.core.reverse_fill.XlsxReader
import com.surveycontroller.android.core.update.UpdateChecker
import com.surveycontroller.android.core.update.UpdateInfo
import com.surveycontroller.android.data.ConfigCodec
import com.surveycontroller.android.data.ConfigCompiler
import com.surveycontroller.android.data.ConfigPreflight
import com.surveycontroller.android.data.QuestionConfigDraft
import com.surveycontroller.android.data.RecentSurvey
import com.surveycontroller.android.data.RecentSurveyStore
import com.surveycontroller.android.data.RunParamsDraft
import com.surveycontroller.android.data.SettingsStore
import com.surveycontroller.android.data.SurveyConfigDraft
import com.surveycontroller.android.data.withBulkDimension
import com.surveycontroller.android.provider.ProviderRegistry
import com.surveycontroller.android.provider.SurveyProviderType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class WorkbenchState(
    val url: String = "",
    val parsing: Boolean = false,
    val error: String? = null,
    val draft: SurveyConfigDraft? = null,
    val reverseFillFileName: String? = null,
    val reverseFillSampleCount: Int = 0,
    val reverseFillEnabled: Boolean = false,
    val reverseFillLoaded: Boolean = false,
    val reverseFillSpec: ReverseFillSpec? = null,
)

data class AccountState(
    val session: RandomIpSession = RandomIpSession(),
    val online: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

/** 整卷批量配置模式。 */
enum class BulkMode { EVEN, RANDOM }

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val registry: ProviderRegistry,
    private val engine: RunEngine,
    private val backend: BackendClient,
    private val recentStore: RecentSurveyStore,
    private val updateChecker: UpdateChecker,
    val settingsStore: SettingsStore,
) : ViewModel() {

    private val _workbench = MutableStateFlow(WorkbenchState())
    val workbench: StateFlow<WorkbenchState> = _workbench.asStateFlow()

    val runProgress: StateFlow<RunProgress> = engine.progress

    val recentSurveys: StateFlow<List<RecentSurvey>> =
        recentStore.recent.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _update = MutableStateFlow<UpdateInfo?>(null)
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()
    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate: StateFlow<Boolean> = _checkingUpdate.asStateFlow()

    // 随机IP账号 / 后端状态
    private val _account = MutableStateFlow(AccountState())
    val account: StateFlow<AccountState> = _account.asStateFlow()

    private var parseJob: Job? = null
    private var parseGeneration = 0
    private var runStartJob: Job? = null
    private var reverseFillSpec: ReverseFillSpec? = null
    private var reverseFillUri: Uri? = null
    private var reverseFillDisplayName: String? = null

    init {
        viewModelScope.launch {
            runCatching { backend.currentSession() }.getOrNull()?.let { s ->
                _account.value = _account.value.copy(session = s)
            }
        }
    }

    fun checkBackendStatus() {
        viewModelScope.launch {
            _account.value = _account.value.copy(busy = true, message = null)
            val (online, msg) = runCatching { backend.status() }.getOrElse { false to "状态查询失败：${it.message}" }
            _account.value = _account.value.copy(busy = false, online = online, message = msg)
        }
    }

    /** 激活 / 恢复随机 IP 账号。v5 起服务端不再保证发放免费试用，失败时引导用卡密激活。 */
    fun activateTrial() {
        viewModelScope.launch {
            _account.value = _account.value.copy(busy = true, message = null)
            runCatching { backend.ensureSession() }
                .onSuccess { session ->
                    val text = when {
                        session.ready -> "账号已就绪"
                        session.authenticated -> "已激活（旧版会话），可点“同步额度”升级登录凭据"
                        else -> "未获得账号，请重试或使用额度卡密激活"
                    }
                    _account.value = _account.value.copy(busy = false, session = session, message = text)
                }
                .onFailure { _account.value = _account.value.copy(busy = false, message = backendErrorMessage(it)) }
        }
    }

    fun refreshQuota() {
        viewModelScope.launch {
            _account.value = _account.value.copy(busy = true, message = null)
            runCatching { backend.syncQuota() }
                .onSuccess { _account.value = _account.value.copy(busy = false, session = it, message = "额度已同步") }
                .onFailure { _account.value = _account.value.copy(busy = false, message = backendErrorMessage(it)) }
        }
    }

    fun redeemCard(code: String) {
        viewModelScope.launch {
            _account.value = _account.value.copy(busy = true, message = null)
            runCatching { backend.redeemCard(code) }
                .onSuccess {
                    _account.value = _account.value.copy(
                        busy = false,
                        session = it,
                        message = if (it.ready) "兑换成功，账号已激活" else "兑换成功",
                    )
                }
                .onFailure { _account.value = _account.value.copy(busy = false, message = backendErrorMessage(it)) }
        }
    }

    fun setUrl(url: String) {
        if (_workbench.value.url == url) return
        invalidateParse()
        _workbench.value = _workbench.value.copy(url = url, parsing = false, error = null)
    }

    private fun invalidateParse() {
        parseGeneration += 1
        parseJob?.cancel()
    }

    fun supportedUrl(url: String): Boolean = SurveyProviderType.isSupportedUrl(url)

    fun parse() {
        invalidateParse()
        val generation = parseGeneration
        val url = SurveyProviderType.normalizeParseUrl(_workbench.value.url)
        if (url.isEmpty()) {
            _workbench.value = _workbench.value.copy(parsing = false, error = "请输入问卷链接")
            return
        }
        _workbench.value = _workbench.value.copy(url = url, parsing = true, error = null)
        parseJob = viewModelScope.launch {
            try {
                val def = registry.parseSurvey(url)
                if (generation != parseGeneration) return@launch
                if (def.questions.isEmpty()) {
                    _workbench.value = _workbench.value.copy(parsing = false, error = "未解析到题目，请确认链接已公开")
                } else {
                    val settings = runCatching { settingsStore.settings.first() }.getOrDefault(SettingsStore.Settings())
                    if (generation != parseGeneration) return@launch
                    val draft = SurveyConfigDraft.fromDefinition(def).let {
                        it.copy(
                            params = it.params.copy(
                                targetNum = settings.defaultTarget,
                                numThreads = settings.defaultThreads,
                            ),
                        )
                    }
                    _workbench.value = _workbench.value.copy(
                        parsing = false,
                        draft = draft,
                        reverseFillFileName = null,
                        reverseFillSampleCount = 0,
                        reverseFillEnabled = false,
                        reverseFillLoaded = false,
                        reverseFillSpec = null,
                    )
                    reverseFillSpec = null
                    reverseFillUri = null
                    reverseFillDisplayName = null
                    recentStore.add(
                        RecentSurvey(
                            url = url,
                            title = def.title.ifEmpty { "未命名问卷" },
                            provider = def.provider.id,
                            questionCount = def.questions.size,
                            timestamp = System.currentTimeMillis(),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == parseGeneration) {
                    _workbench.value = _workbench.value.copy(parsing = false, error = e.message ?: "解析失败")
                }
            }
        }
    }

    fun updateQuestion(index: Int, transform: (QuestionConfigDraft) -> QuestionConfigDraft) {
        val draft = _workbench.value.draft ?: return
        val list = draft.questions.toMutableList()
        if (index !in list.indices) return
        list[index] = transform(list[index])
        _workbench.value = _workbench.value.copy(draft = draft.copy(questions = list))
    }

    fun updateParams(transform: (RunParamsDraft) -> RunParamsDraft) {
        val draft = _workbench.value.draft ?: return
        _workbench.value = _workbench.value.copy(draft = draft.copy(params = transform(draft.params)))
    }

    /** 新增一条条件规则。 */
    fun addAnswerRule(rule: Map<String, Any?>) {
        val draft = _workbench.value.draft ?: return
        val sanitized = AnswerRule.sanitizeRules(listOf(rule), draft.definition.questions).firstOrNull() ?: return
        _workbench.value = _workbench.value.copy(draft = draft.copy(answerRules = draft.answerRules + sanitized))
    }

    /** 删除指定索引的条件规则。 */
    fun removeAnswerRule(index: Int) {
        val draft = _workbench.value.draft ?: return
        if (index !in draft.answerRules.indices) return
        _workbench.value = _workbench.value.copy(
            draft = draft.copy(answerRules = draft.answerRules.toMutableList().also { it.removeAt(index) }),
        )
    }

    /** 整卷批量操作：均分 / 随机 所有题的选项权重。 */
    fun bulkApply(mode: BulkMode) {
        val draft = _workbench.value.draft ?: return
        val updated = draft.questions.map { q -> applyBulk(q, mode) }
        _workbench.value = _workbench.value.copy(draft = draft.copy(questions = updated))
    }

    /** 批量设置信度维度。Android 先覆盖量表/评价/矩阵题，避免把普通选择题误混入信度优化。 */
    fun bulkApplyDimension(dimension: String?) {
        val draft = _workbench.value.draft ?: return
        _workbench.value = _workbench.value.copy(draft = draft.withBulkDimension(dimension))
    }

    private fun applyBulk(q: QuestionConfigDraft, mode: BulkMode): QuestionConfigDraft {
        fun evenWeights(n: Int): MutableList<Double> {
            if (n <= 0) return mutableListOf()
            val each = 100 / n
            return MutableList(n) { if (it < n - 1) each.toDouble() else (100 - each * (n - 1)).toDouble() }
        }
        val optCount = q.optionTexts.size
        return when (q.entryType) {
            QuestionEntryType.SINGLE, QuestionEntryType.DROPDOWN,
            QuestionEntryType.SCALE, QuestionEntryType.SCORE ->
                if (mode == BulkMode.RANDOM) {
                    // 完全随机：等概率随机作答
                    q.copy(distributionMode = "random", biasPreset = "custom")
                } else {
                    // 均分：自定义配比 + 等权重（运行时严格收敛到均等比例）
                    q.copy(distributionMode = "custom", biasPreset = "custom", optionWeights = evenWeights(optCount))
                }
            QuestionEntryType.MATRIX ->
                if (mode == BulkMode.RANDOM) {
                    q.copy(distributionMode = "random", matrixBiasPresets = MutableList(maxOf(1, q.matrixRowWeights.size, q.rowTexts.size)) { "custom" })
                } else {
                    q.copy(
                        distributionMode = "custom",
                        matrixRowWeights = q.matrixRowWeights.map { evenWeights(optCount) }.toMutableList(),
                        matrixBiasPresets = MutableList(maxOf(1, q.matrixRowWeights.size, q.rowTexts.size)) { "custom" },
                    )
                }
            QuestionEntryType.MULTIPLE ->
                q.copy(multiProbabilities = MutableList(optCount) { if (mode == BulkMode.RANDOM) kotlin.random.Random.nextInt(0, 101).toDouble() else 50.0 })
            else -> q
        }
    }

    internal suspend fun rehearse(seed: Long): OfflineRehearsal.Report {
        val snapshot = _workbench.value
        val draft = snapshot.draft ?: throw OfflineRehearsal.Issue("请先解析问卷并配置答案")
        if (engine.isRunning) throw OfflineRehearsal.Issue("请在当前任务结束后试跑")
        val spec = if (snapshot.reverseFillEnabled) {
            snapshot.reverseFillSpec ?: throw OfflineRehearsal.Issue("请先导入可用的反填数据")
        } else {
            null
        }
        return withContext(Dispatchers.Default) {
            val ids = ConfigCodec.resolvedQuestionIds(draft)
            val numbers = draft.definition.questions.filter { it.num > 0 && !it.isDescription }.map { it.num }
            if (ids.size != ids.toSet().size || numbers.size != numbers.toSet().size) {
                throw OfflineRehearsal.Issue("题号或题目 ID 重复，请重新解析问卷")
            }
            OfflineRehearsal.generate(ConfigCompiler.compile(draft), seed, spec)
        }
    }

    fun startRun(onStarted: (() -> Unit)? = null) {
        if (runStartJob?.isActive == true || engine.isRunning) return
        val workbench = _workbench.value
        val draft = workbench.draft ?: return
        if (workbench.parsing || SurveyProviderType.normalizeParseUrl(workbench.url) != draft.definition.url) return
        runStartJob = viewModelScope.launch {
            val s = settingsStore.settings.first()
            if (_workbench.value !== workbench) return@launch
            val reverseFillEnabled = workbench.reverseFillEnabled
            val runtimeDraft = if (reverseFillEnabled) {
                draft.copy(params = draft.params.copy(numThreads = draft.preserved.reverseFillThreads.coerceIn(1, 16)))
            } else {
                draft
            }
            val localAi = ConfigCompiler.AiOptions(
                enabled = s.aiEnabled,
                baseUrl = s.aiBaseUrl,
                apiKey = s.aiApiKey,
                model = s.aiModel,
                apiProtocol = s.aiApiProtocol,
                systemPrompt = s.aiSystemPrompt,
                submissionReportEnabled = s.submissionReportTelemetry,
            )
            val (effectiveParams, ai) = ConfigCompiler.resolveAiOptions(runtimeDraft, localAi)
            val effectiveDraft = runtimeDraft.copy(params = effectiveParams)
            val preflight = ConfigPreflight.validate(
                effectiveDraft,
                ConfigPreflight.Options(
                    customAiEnabled = ai.enabled,
                    customAiBaseUrl = ai.baseUrl,
                    customAiApiKey = ai.apiKey,
                    customAiModel = ai.model,
                    customAiApiProtocol = ai.apiProtocol,
                    reverseFillEnabled = reverseFillEnabled,
                    reverseFillSampleCount = _workbench.value.reverseFillSampleCount,
                    reverseFillSpec = _workbench.value.reverseFillSpec,
                ),
            )
            if (!preflight.canStart) {
                _workbench.value = _workbench.value.copy(error = preflight.blockingMessage())
                return@launch
            }
            val config = ConfigCompiler.compile(effectiveDraft, ai)
            val spec = if (_workbench.value.reverseFillEnabled) reverseFillSpec else null
            RunForegroundService.start(appContext, "正在执行问卷任务…")
            engine.start(config, viewModelScope, spec)
            _workbench.value = _workbench.value.copy(error = null)
            onStarted?.invoke()
            val result = engine.progress.first {
                it.status == RunStatus.FINISHED || it.status == RunStatus.FAILED || it.status == RunStatus.IDLE
            }
            RunForegroundService.stop(appContext)
            if (result.status != RunStatus.IDLE && s.autoSaveLogs) {
                RunLogArchiver.save(
                    context = appContext,
                    header = listOf(
                        "问卷：${draft.definition.title.ifEmpty { "未命名问卷" }}",
                        "链接：${draft.definition.url}",
                        "结果：成功 ${result.success} 份 · 失败 ${result.fail} 份",
                        "状态：${if (result.status == RunStatus.FINISHED) "已完成" else "已停止"}",
                    ),
                    lines = result.recentLogs,
                    retentionCount = s.logRetentionCount,
                )
            }
            if (s.taskResultNotification && result.status != RunStatus.IDLE) {
                RunForegroundService.notifyResult(
                    appContext,
                    if (result.status == RunStatus.FINISHED) "任务完成" else "任务已停止",
                    "成功 ${result.success} 份 · 失败 ${result.fail} 份",
                )
            }
        }
    }

    /** 从用户选择的 xlsx 构建反向填充样本。 */
    fun loadReverseFill(uri: Uri, fileName: String) {
        val draft = _workbench.value.draft ?: return
        if (draft.definition.provider != SurveyProviderType.WJX) {
            reverseFillSpec = null
            reverseFillUri = null
            reverseFillDisplayName = null
            _workbench.value = _workbench.value.copy(
                reverseFillSampleCount = 0,
                reverseFillEnabled = false,
                reverseFillLoaded = false,
                reverseFillSpec = null,
                error = "反向填充当前仅支持问卷星导出的 xlsx",
            )
            return
        }
        reverseFillUri = uri
        reverseFillDisplayName = fileName
        appContext.takePersistableReadPermissionSafely(uri)
        rebuildReverseFill(draft, fileName)
    }

    private fun rebuildReverseFill(sourceDraft: SurveyConfigDraft, fileName: String) {
        viewModelScope.launch {
            try {
                val uri = reverseFillUri ?: error("请先选择 xlsx 数据文件")
                val currentBeforeRead = _workbench.value.draft ?: sourceDraft
                val preserved = currentBeforeRead.preserved
                val spec = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "无法读取文件" }
                        val rows = XlsxReader.read(input)
                        ReverseFillBuilder.build(
                            rows = rows,
                            questions = currentBeforeRead.definition.questions,
                            startRow = preserved.reverseFillStartRow,
                            format = preserved.reverseFillFormat,
                            targetNum = currentBeforeRead.params.targetNum,
                        )
                    }
                }
                val currentDraft = _workbench.value.draft ?: sourceDraft
                val updatedDraft = currentDraft.copy(
                    preserved = currentDraft.preserved.copy(
                        reverseFillEnabled = spec.totalSamples > 0,
                        reverseFillSourcePath = fileName,
                        reverseFillThreads = currentDraft.preserved.reverseFillThreads.coerceAtLeast(1),
                    ),
                )
                reverseFillSpec = spec
                _workbench.value = _workbench.value.copy(
                    draft = updatedDraft,
                    reverseFillFileName = fileName,
                    reverseFillSampleCount = spec.totalSamples,
                    reverseFillEnabled = spec.totalSamples > 0,
                    reverseFillLoaded = true,
                    reverseFillSpec = spec,
                    error = null,
                )
            } catch (e: Exception) {
                reverseFillSpec = null
                _workbench.value = _workbench.value.copy(
                    reverseFillSampleCount = 0,
                    reverseFillEnabled = false,
                    reverseFillLoaded = false,
                    reverseFillSpec = null,
                    error = "反向填充解析失败：${e.message}",
                )
            }
        }
    }

    fun setReverseFillEnabled(enabled: Boolean) {
        val state = _workbench.value
        val nextEnabled = enabled && state.reverseFillLoaded && state.reverseFillSampleCount > 0
        val nextDraft = state.draft?.let { draft ->
            draft.copy(
                preserved = draft.preserved.copy(reverseFillEnabled = nextEnabled),
                params = if (nextEnabled) draft.params.copy(numThreads = draft.preserved.reverseFillThreads.coerceIn(1, 16)) else draft.params,
            )
        }
        _workbench.value = state.copy(draft = nextDraft, reverseFillEnabled = nextEnabled)
    }

    fun updateReverseFillFormat(format: String) {
        val state = _workbench.value
        val draft = state.draft ?: return
        val normalized = normalizeReverseFillFormat(format)
        val nextDraft = draft.copy(preserved = draft.preserved.copy(reverseFillFormat = normalized))
        _workbench.value = state.copy(draft = nextDraft)
        if (state.reverseFillLoaded) {
            rebuildReverseFill(nextDraft, state.reverseFillFileName ?: reverseFillDisplayName ?: "导入文件")
        }
    }

    fun updateReverseFillStartRow(value: Int) {
        val state = _workbench.value
        val draft = state.draft ?: return
        val nextDraft = draft.copy(preserved = draft.preserved.copy(reverseFillStartRow = value.coerceIn(1, 9999)))
        _workbench.value = state.copy(draft = nextDraft)
        if (state.reverseFillLoaded) {
            rebuildReverseFill(nextDraft, state.reverseFillFileName ?: reverseFillDisplayName ?: "导入文件")
        }
    }

    fun updateReverseFillThreads(value: Int) {
        val state = _workbench.value
        val draft = state.draft ?: return
        val threads = value.coerceIn(1, 16)
        val nextDraft = draft.copy(
            preserved = draft.preserved.copy(reverseFillThreads = threads),
            params = if (state.reverseFillEnabled) draft.params.copy(numThreads = threads) else draft.params,
        )
        _workbench.value = state.copy(draft = nextDraft)
    }

    /** 导出当前配置为 JSON（schema v6，兼容桌面端）。 */
    fun exportConfig(uri: Uri) {
        val draft = _workbench.value.draft ?: return
        viewModelScope.launch {
            try {
                val json = ConfigCodec.serialize(draft)
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                }
                _workbench.value = _workbench.value.copy(error = null)
            } catch (e: Exception) {
                _workbench.value = _workbench.value.copy(error = "配置导出失败：${e.message}")
            }
        }
    }

    /** 导入配置 JSON，恢复问卷与各题配置。 */
    fun importConfig(uri: Uri) {
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri).use { it?.readBytes()?.toString(Charsets.UTF_8) ?: "" }
                }
                val draft = ConfigCodec.deserialize(json)
                val importedReverseName = draft.preserved.reverseFillSourcePath
                    .trim()
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .takeIf { draft.preserved.reverseFillEnabled && it.isNotBlank() }
                reverseFillSpec = null
                reverseFillUri = null
                reverseFillDisplayName = importedReverseName
                invalidateParse()
                _workbench.value = _workbench.value.copy(
                    url = draft.definition.url,
                    parsing = false,
                    draft = draft,
                    error = null,
                    reverseFillFileName = importedReverseName,
                    reverseFillSampleCount = 0,
                    reverseFillEnabled = false,
                    reverseFillLoaded = false,
                    reverseFillSpec = null,
                )
            } catch (e: Exception) {
                _workbench.value = _workbench.value.copy(error = "配置导入失败：${e.message}")
            }
        }
    }

    fun pauseRun() = engine.pause()
    fun resumeRun() = engine.resume()
    fun stopRun() {
        if (!engine.isRunning) runStartJob?.cancel()
        viewModelScope.launch {
            engine.stop()
            RunForegroundService.stop(appContext)
        }
    }

    fun reset() {
        invalidateParse()
        _workbench.value = WorkbenchState()
        reverseFillSpec = null
        reverseFillUri = null
        reverseFillDisplayName = null
    }

    /** 从历史记录回填链接并立即解析。 */
    fun useRecent(url: String) {
        setUrl(url)
        parse()
    }

    fun clearHistory() {
        viewModelScope.launch { recentStore.clear() }
    }

    /** 检查更新（GitHub Releases）。 */
    fun checkUpdate() {
        if (_checkingUpdate.value) return
        viewModelScope.launch {
            _checkingUpdate.value = true
            _update.value = updateChecker.check()
            _checkingUpdate.value = false
        }
    }

    // ===== 系统工具 =====

    private val _toolMessage = MutableStateFlow<String?>(null)
    val toolMessage: StateFlow<String?> = _toolMessage.asStateFlow()

    private val _logFileCount = MutableStateFlow(RunLogArchiver.count(appContext))
    val logFileCount: StateFlow<Int> = _logFileCount.asStateFlow()

    /** 应用私有日志目录路径（供“配置/数据目录”展示）。 */
    val logsDirectoryPath: String get() = RunLogArchiver.logsDir(appContext).absolutePath

    fun refreshLogCount() {
        _logFileCount.value = RunLogArchiver.count(appContext)
    }

    /** 恢复默认设置（对齐桌面端 reset_ui_card）。 */
    fun resetSettings() {
        viewModelScope.launch {
            settingsStore.resetToDefaults()
            _toolMessage.value = "已恢复默认设置"
        }
    }

    fun applyImportedAiConfig() {
        val ai = _workbench.value.draft?.preserved?.importedAiConfig ?: return
        if (!ai.present) {
            _toolMessage.value = "当前配置没有导入的 AI 设置"
            return
        }
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(
                    aiEnabled = ai.isProviderMode,
                    aiBaseUrl = ai.baseUrl,
                    aiApiKey = ai.apiKey,
                    aiModel = ai.model,
                    aiApiProtocol = SettingsStore.normalizeAiApiProtocol(ai.apiProtocol),
                    aiSystemPrompt = ai.systemPrompt,
                )
            }
            _toolMessage.value = "已采用导入的自定义 AI 配置"
        }
    }

    /** 清空已归档运行日志。 */
    fun clearLogs() {
        viewModelScope.launch {
            val n = RunLogArchiver.clear(appContext)
            _logFileCount.value = RunLogArchiver.count(appContext)
            _toolMessage.value = if (n > 0) "已清除 $n 份日志" else "暂无可清除的日志"
        }
    }

    /** 把最近一份运行日志导出到用户选择的位置。 */
    fun exportLatestLog(uri: Uri) {
        viewModelScope.launch {
            val latest = RunLogArchiver.listFiles(appContext).firstOrNull()
            if (latest == null) {
                _toolMessage.value = "暂无可导出的日志"
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        latest.inputStream().use { it.copyTo(out) }
                    }
                }
            }.onSuccess { _toolMessage.value = "日志已导出" }
                .onFailure { _toolMessage.value = "导出失败：${it.message}" }
        }
    }

    fun clearToolMessage() {
        _toolMessage.value = null
    }

    // ===== IP 用量记录 =====

    data class IpUsageState(
        val loading: Boolean = false,
        val summary: IpUsageSummary? = null,
        val error: String? = null,
        val bonusMessage: String? = null,
    )

    private val _ipUsage = MutableStateFlow(IpUsageState())
    val ipUsage: StateFlow<IpUsageState> = _ipUsage.asStateFlow()

    /** 加载 IP 用量记录（每日提取数 + 池剩余）。 */
    fun loadIpUsage() {
        if (_ipUsage.value.loading) return
        _ipUsage.value = _ipUsage.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { backend.ipUsageSummary() }
                .onSuccess { _ipUsage.value = _ipUsage.value.copy(loading = false, summary = it) }
                .onFailure { _ipUsage.value = _ipUsage.value.copy(loading = false, error = it.message ?: "获取失败") }
        }
    }

    /** 领取彩蛋隐藏福利（进入 IP 用量页触发，对齐桌面端 claim_easter_egg_bonus）。 */
    fun claimEasterEggBonus() {
        viewModelScope.launch {
            if (!_account.value.session.authenticated) {
                _ipUsage.value = _ipUsage.value.copy(bonusMessage = "🎉 恭喜发现彩蛋，激活随机 IP 后可领取隐藏福利")
                return@launch
            }
            runCatching { backend.claimBonus() }
                .onSuccess { s ->
                    _account.value = _account.value.copy(session = s)
                    _ipUsage.value = _ipUsage.value.copy(bonusMessage = "🎉 恭喜发现彩蛋，隐藏福利已到账")
                }
                .onFailure { /* 已领取或失败：静默 */ }
        }
    }

    fun clearBonusMessage() {
        _ipUsage.value = _ipUsage.value.copy(bonusMessage = null)
    }

}

private fun Context.takePersistableReadPermissionSafely(uri: Uri) {
    runCatching {
        contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}

private fun normalizeReverseFillFormat(format: String): String {
    val value = format.trim().lowercase().ifBlank { "auto" }
    return if (value in setOf("auto", "wjx_sequence", "wjx_score", "wjx_text")) value else "auto"
}
