package com.surveycontroller.android.core.engine

import com.surveycontroller.android.app.AppVersion
import com.surveycontroller.android.core.backend.BackendClient
import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.model.ExecutionState
import com.surveycontroller.android.core.network.BackendProxyPool
import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.network.ProxyPool
import com.surveycontroller.android.core.psychometrics.JointOptimizer
import com.surveycontroller.android.core.questions.AiTextException
import com.surveycontroller.android.core.questions.PsychoPlanLookup
import com.surveycontroller.android.core.questions.SamplePlanProvider
import com.surveycontroller.android.core.reverse_fill.ReverseFillRuntime
import com.surveycontroller.android.core.reverse_fill.ReverseFillSpec
import com.surveycontroller.android.provider.ProviderRegistry
import com.surveycontroller.android.provider.SubmitResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

enum class RunStatus { IDLE, RUNNING, PAUSED, STOPPING, FINISHED, FAILED }

data class RunProgress(
    val status: RunStatus = RunStatus.IDLE,
    val target: Int = 0,
    val success: Int = 0,
    val fail: Int = 0,
    val message: String = "",
    val recentLogs: List<String> = emptyList(),
    val slotStatuses: Map<String, SlotStatus> = emptyMap(),
    val failureReasons: Map<String, Int> = emptyMap(),
    val elapsedMs: Long = 0,
)

/** 单个并发槽的实时状态。 */
data class SlotStatus(
    val name: String,
    val step: String = "等待",
    val running: Boolean = false,
    val success: Int = 0,
    val fail: Int = 0,
)

/**
 * 协程并发运行引擎。复刻 software/core/engine/async_engine.py 的多槽调度 + 停止/暂停/恢复 + 目标份数。
 */
class RunEngine(
    private val registry: ProviderRegistry,
    private val httpClient: HttpClient = HttpClient(),
    private val backend: BackendClient? = null,
    private val proxyPoolFactory: ((ExecutionConfig) -> ProxyProvider?)? = null,
) {
    private companion object {
        const val MAX_WORKERS = 16
    }

    private val _progress = MutableStateFlow(RunProgress())
    val progress: StateFlow<RunProgress> = _progress.asStateFlow()

    private var job: Job? = null
    private val stopFlag = AtomicBoolean(false)
    private val pauseFlag = AtomicBoolean(false)
    private val logs = ArrayDeque<String>()
    private val logLock = Any()
    private var reverseRuntime: ReverseFillRuntime? = null
    private var startTimeMs: Long = 0
    private val slotStatuses = ConcurrentHashMap<String, SlotStatus>()
    private val failureReasons = ConcurrentHashMap<String, Int>()
    private val proxyUnavailableCount = AtomicInteger(0)
    private val aiFailureCount = AtomicInteger(0)

    private fun setSlot(name: String, step: String, running: Boolean) {
        val prev = slotStatuses[name] ?: SlotStatus(name)
        slotStatuses[name] = prev.copy(step = step, running = running)
    }

    private fun slotResult(name: String, success: Boolean) {
        val prev = slotStatuses[name] ?: SlotStatus(name)
        slotStatuses[name] = if (success) prev.copy(success = prev.success + 1) else prev.copy(fail = prev.fail + 1)
    }

    private fun classifyFailure(result: SubmitResult): String = when (result) {
        is SubmitResult.Verification -> "验证码/风控"
        is SubmitResult.Rejected -> "提交被拒"
        is SubmitResult.Failure -> when {
            result.message.contains("代理") || result.message.contains("proxy", true) -> "代理/网络"
            result.message.contains("超时") || result.message.contains("timeout", true) -> "超时"
            else -> "其他失败"
        }
        else -> "其他失败"
    }

    private fun recordFailure(result: SubmitResult) {
        val key = classifyFailure(result)
        failureReasons.merge(key, 1) { a, b -> a + b }
    }

    val isRunning: Boolean get() = job?.isActive == true

    fun start(config: ExecutionConfig, parentScope: CoroutineScope, reverseFillSpec: ReverseFillSpec? = null) {
        if (isRunning) return
        stopFlag.set(false)
        pauseFlag.set(false)
        val state = ExecutionState(config)
        val target = maxOf(1, config.targetNum)
        val configuredWorkers = config.numThreads.coerceIn(1, MAX_WORKERS)
        val workerCount = if (reverseFillSpec != null) {
            maxOf(1, minOf(configuredWorkers, target))
        } else {
            configuredWorkers
        }
        val claimed = AtomicInteger(0)

        // 心理测量联合计划（信度模式 + 存在维度时）
        val jointPlan = if (config.reliabilityModeEnabled) JointOptimizer.build(config) else null
        val sampleByThread = ConcurrentHashMap<String, Int>()
        val sampleCounter = AtomicInteger(0)
        if (jointPlan != null) {
            state.samplePlanProvider = object : SamplePlanProvider {
                override fun planFor(threadName: String): PsychoPlanLookup? {
                    val idx = sampleByThread[threadName] ?: return null
                    return jointPlan.samplePlan(idx % jointPlan.sampleCount)
                }
            }
            log("已启用信度联合优化：锁定 $target 份样本的量表作答")
        }

        // 反向填充运行时
        reverseRuntime = reverseFillSpec?.let { ReverseFillRuntime(it) }
        reverseRuntime?.let {
            state.reverseFillResolver = it
            log("已启用反向填充：可回放样本 ${it.remaining} 份")
        }

        // 代理池：default/benefit 走项目后端，custom 走自定义 API
        val proxyPool: ProxyProvider? = if (config.randomProxyIpEnabled) resolveProxyPool(config) else null
        if (config.randomProxyIpEnabled && proxyPool == null) {
            startTimeMs = System.currentTimeMillis()
            slotStatuses.clear()
            failureReasons.clear()
            val message = "随机 IP 已开启，但当前代理源不可用；请检查代理配置或关闭随机 IP"
            state.markTerminalStop("proxy_unavailable_threshold", failureReason = "proxy_unavailable", message = message)
            log(message)
            _progress.value = RunProgress(RunStatus.FAILED, target, 0, 0, message, recentLogs = snapshotLogs())
            return
        }

        log("任务启动：目标 $target 份，并发 $workerCount，平台 ${config.surveyProvider}")
        startTimeMs = System.currentTimeMillis()
        slotStatuses.clear()
        failureReasons.clear()
        proxyUnavailableCount.set(0)
        aiFailureCount.set(0)
        (1..workerCount).forEach { slotStatuses["Slot-$it"] = SlotStatus("Slot-$it") }
        _progress.value = RunProgress(RunStatus.RUNNING, target, 0, 0, "运行中", slotStatuses = slotStatuses.toMap())

        val supervisor = SupervisorJob(parentScope.coroutineContext[Job])
        val scope = CoroutineScope(parentScope.coroutineContext + supervisor + Dispatchers.IO)
        job = scope.launch {
            val workers = (1..workerCount).map { slot ->
                launch { runWorker("Slot-$slot", config, state, target, workerCount, claimed, proxyPool, jointPlan, sampleByThread, sampleCounter) }
            }
            workers.forEach { it.join() }
            finish(state, target)
        }
    }

    private suspend fun runWorker(
        name: String,
        config: ExecutionConfig,
        state: ExecutionState,
        target: Int,
        workerCount: Int,
        claimed: AtomicInteger,
        proxyPool: ProxyProvider?,
        jointPlan: JointOptimizer.AnswerPlan?,
        sampleByThread: ConcurrentHashMap<String, Int>,
        sampleCounter: AtomicInteger,
    ) {
        while (currentCoroutineContext().isActive) {
            if (stopFlag.get()) break
            if (state.curNum >= target) break
            while (pauseFlag.get() && !stopFlag.get()) {
                _progress.update { it.copy(status = RunStatus.PAUSED) }
                delay(200)
            }
            if (stopFlag.get()) break
            // 认领一个名额，避免并发超额
            if (claimed.incrementAndGet() > target) {
                claimed.decrementAndGet()
                break
            }
            // 预约心理测量样本与反向填充样本
            if (jointPlan != null && sampleByThread[name] == null) {
                sampleByThread[name] = sampleCounter.getAndIncrement()
            }
            val reverseOk = reverseRuntime?.reserve(name) ?: true
            if (!reverseOk) {
                claimed.decrementAndGet()
                stopWithReason(state, "反填样本已耗尽，剩余样本不足以完成目标份数", category = "reverse_fill_exhausted")
                break
            }
            // v4.0.6 / #95：UA 选择独立化——循环开头按比例选出，无论代理成功与否都先选出 UA
            val ua = pickUserAgent(config)
            // 代理获取移到 fillSurveyHttp 调用前；代理失败再处理 continue
            val proxy = if (config.randomProxyIpEnabled) proxyPool?.acquire(config) else null
            if (config.randomProxyIpEnabled && proxy == null) {
                claimed.decrementAndGet()
                sampleByThread.remove(name)
                reverseRuntime?.discard(name, requeue = true)
                slotResult(name, false)
                failureReasons.merge("代理不可用", 1) { a, b -> a + b }
                val f = proxyUnavailableCount.incrementAndGet()
                setSlot(name, "代理不可用", running = false)
                log("[$name] 未获取到随机 IP，本轮已跳过提交")
                publish(state, target, RunStatus.RUNNING)
                if (RunStopPolicy.shouldStopOnProxyUnavailable(config, f, workerCount)) {
                    stopWithReason(
                        state,
                        "随机 IP 连续不可用，已停止；请检查代理额度、地区或代理 API",
                        category = "proxy_unavailable_threshold",
                        failureReason = "proxy_unavailable",
                    )
                    break
                }
                val interval = sampleInterval(config)
                if (interval > 0) delay(interval * 1000L)
                continue
            }
            // 对标核心库提交生命周期修复：停止信号在提交准备阶段到达时不得继续 POST。
            if (stopFlag.get() || !currentCoroutineContext().isActive) {
                claimed.decrementAndGet()
                sampleByThread.remove(name)
                reverseRuntime?.discard(name, requeue = true)
                break
            }
            setSlot(name, "提交中", running = true)
            val result = try {
                registry.fillSurveyHttp(config, state, name, proxy, ua)
            } catch (e: AiTextException) {
                SubmitResult.AiFailure(e.message ?: "AI 填空失败")
            } catch (e: Exception) {
                SubmitResult.Failure(e.message ?: e.toString())
            }
            when (result) {
                is SubmitResult.Success -> {
                    state.resetFail()
                    proxyUnavailableCount.set(0)
                    aiFailureCount.set(0)
                    val n = state.incrementSuccess()
                    proxy?.let { proxyPool?.markSuccess(it) }
                    sampleByThread.remove(name)
                    reverseRuntime?.commit(name)
                    slotResult(name, true)
                    setSlot(name, "成功", running = false)
                    log("[$name] 第 $n 份提交成功")
                    publish(state, target, RunStatus.RUNNING)
                    reportSubmission(config, "success")
                }
                is SubmitResult.Verification -> {
                    claimed.decrementAndGet()
                    reverseRuntime?.discard(name, requeue = true)
                    slotResult(name, false)
                    recordFailure(result)
                    reportSubmission(config, "fail")
                    if (config.pauseOnAliyunCaptcha) {
                        // 命中风控/验证码：暂停任务等待人工处理（复刻 pause_on_aliyun_captcha）
                        pauseFlag.set(true)
                        setSlot(name, "命中验证码", running = false)
                        log("[$name] 命中验证码/风控，已暂停任务，请人工处理后继续")
                        _progress.update { it.copy(status = RunStatus.PAUSED, message = "命中验证码/风控，已暂停") }
                    } else {
                        val f = state.incrementFail()
                        setSlot(name, "命中验证码", running = false)
                        log("[$name] 命中验证码/风控")
                        publish(state, target, RunStatus.RUNNING)
                        if (RunStopPolicy.shouldStopOnFail(config, f, workerCount)) { stopWithReason(state, "连续失败达到阈值，已停止"); break }
                    }
                }
                is SubmitResult.AiFailure -> {
                    claimed.decrementAndGet()
                    sampleByThread.remove(name)
                    reverseRuntime?.discard(name, requeue = true)
                    slotResult(name, false)
                    failureReasons.merge("AI填空", 1) { a, b -> a + b }
                    val f = aiFailureCount.incrementAndGet()
                    setSlot(name, "AI失败", running = false)
                    log("[$name] AI填空失败，本轮已跳过：${result.message}")
                    publish(state, target, RunStatus.RUNNING)
                    reportSubmission(config, "fail")
                    if (RunStopPolicy.shouldStopOnAiFailure(f)) {
                        stopWithReason(
                            state,
                            "AI 填空连续失败达到 ${RunStopPolicy.AI_FILL_FAIL_THRESHOLD} 次，已停止；请检查 AI 配置或稍后重试",
                            category = "free_ai_unstable",
                            failureReason = "ai_failure",
                        )
                        break
                    }
                }
                is SubmitResult.ProviderUnavailable -> {
                    claimed.decrementAndGet()
                    sampleByThread.remove(name)
                    reverseRuntime?.discard(name, requeue = true)
                    slotResult(name, false)
                    failureReasons.merge("问卷不可填写", 1) { a, b -> a + b }
                    setSlot(name, "问卷不可填写", running = false)
                    log("[$name] 问卷当前不可填写：${result.message}")
                    publish(state, target, RunStatus.RUNNING)
                    reportSubmission(config, "fail")
                    stopWithReason(
                        state,
                        result.message.ifBlank { "问卷当前不可填写" },
                        category = "survey_provider_unavailable",
                        failureReason = "survey_provider_unavailable",
                    )
                    break
                }
                else -> {
                    claimed.decrementAndGet()
                    val discardedSample = reverseRuntime?.markFailed(name, maxRetries = 1) == true
                    slotResult(name, false)
                    recordFailure(result)
                    val f = state.incrementFail()
                    val reason = when (result) {
                        is SubmitResult.Rejected -> "被拒绝${result.questionNum?.let { "（第${it}题）" } ?: ""}：${result.reason}"
                        is SubmitResult.Failure -> result.message
                        else -> "失败"
                    }
                    setSlot(name, "失败", running = false)
                    log("[$name] 提交$reason")
                    if (discardedSample) log("[$name] 当前反填样本连续失败 2 次，已作废")
                    publish(state, target, RunStatus.RUNNING)
                    reportSubmission(config, "fail")
                    if (reverseRuntime?.canReachTarget(state.curNum, target) == false) {
                        stopWithReason(state, "反填样本已耗尽，剩余样本不足以完成目标份数", category = "reverse_fill_exhausted")
                        break
                    }
                    if (RunStopPolicy.shouldStopOnFail(config, f, workerCount)) { stopWithReason(state, "连续失败达到阈值，已停止"); break }
                }
            }
            val interval = sampleInterval(config)
            if (interval > 0) delay(interval * 1000L)
        }
    }

    private suspend fun reportSubmission(config: ExecutionConfig, result: String) {
        if (!config.submissionReportEnabled) return
        val client = backend ?: return
        val provider = when (config.proxySource) {
            "benefit" -> "idiot"
            "default" -> "default"
            else -> "unknown"
        }
        try {
            client.reportSubmission(config.url, result, provider, AppVersion.VERSION)
        } catch (e: Exception) {
            // 上报失败不影响主流程
        }
    }

    private fun resolveProxyPool(config: ExecutionConfig): ProxyProvider? =
        proxyPoolFactory?.invoke(config) ?: when {
            config.proxySource == "custom" && config.customProxyApi.isNotBlank() -> ProxyPool(httpClient, config.customProxyApi)
            config.proxySource == "benefit" && backend != null -> BackendProxyPool(backend, "benefit")
            config.proxySource == "default" && backend != null -> BackendProxyPool(backend, "default")
            config.customProxyApi.isNotBlank() -> ProxyPool(httpClient, config.customProxyApi)
            else -> null
        }

    private fun stopWithReason(state: ExecutionState, reason: String, category: String = "fail", failureReason: String = "") {
        stopFlag.set(true)
        state.markTerminalStop(category, failureReason = failureReason, message = reason)
        log(reason)
    }

    private fun pickUserAgent(config: ExecutionConfig): String? {
        if (!config.randomUserAgentEnabled) return null
        // v4.0.6 / #95：UA 选择独立化，改用 UserAgents.selectUserAgentFromRatios（对齐官方 _select_user_agent_from_ratios）
        val profile = com.surveycontroller.android.core.network.UserAgents.selectUserAgentFromRatios(config.userAgentRatios)
            ?: return null
        return profile.ua
    }

    private fun sampleInterval(config: ExecutionConfig): Int {
        val r = config.submitIntervalRangeSeconds
        val lo = r.first.coerceAtLeast(0)
        val hi = r.last.coerceAtLeast(lo)
        return if (hi > lo) Random.nextInt(lo, hi + 1) else lo
    }

    private fun finish(state: ExecutionState, target: Int) {
        val status = when {
            state.terminalStopCategory.isNotEmpty() && state.curNum < target -> RunStatus.FAILED
            stopFlag.get() && state.curNum < target -> RunStatus.IDLE
            else -> RunStatus.FINISHED
        }
        val msg = when (status) {
            RunStatus.FINISHED -> "已完成，共成功 ${state.curNum} 份"
            RunStatus.FAILED -> state.terminalStopMessage.ifEmpty { "任务异常停止" }
            else -> "已停止，成功 ${state.curNum} 份"
        }
        log(msg)
        publish(state, target, status, msg)
    }

    private fun publish(state: ExecutionState, target: Int, status: RunStatus, message: String = "") {
        _progress.value = RunProgress(
            status = status,
            target = target,
            success = state.curNum,
            fail = state.curFail,
            message = message.ifEmpty { _progress.value.message },
            recentLogs = snapshotLogs(),
            slotStatuses = slotStatuses.toMap(),
            failureReasons = failureReasons.toMap(),
            elapsedMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0,
        )
    }

    private fun log(line: String) {
        synchronized(logLock) {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            logs.addLast("$ts  $line")
            while (logs.size > 500) logs.removeFirst()
        }
    }

    private fun snapshotLogs(): List<String> = synchronized(logLock) { logs.toList() }

    fun pause() {
        if (!isRunning) return
        pauseFlag.set(true)
        _progress.update { it.copy(status = RunStatus.PAUSED, message = "已暂停") }
    }

    fun resume() {
        if (!isRunning) return
        pauseFlag.set(false)
        _progress.update { it.copy(status = RunStatus.RUNNING, message = "运行中") }
    }

    suspend fun stop() {
        stopFlag.set(true)
        _progress.update { it.copy(status = RunStatus.STOPPING, message = "停止中") }
        job?.cancelAndJoin()
        job = null
    }
}

/** 代理 IP 提供者抽象。阶段5具体实现。 */
interface ProxyProvider {
    suspend fun acquire(config: ExecutionConfig): String?
    fun markSuccess(proxyAddress: String) {}
}

private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    value = transform(value)
}
