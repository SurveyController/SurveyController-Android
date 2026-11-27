package com.surveycontroller.android.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一次任务运行中的动态状态（线程安全）。
 * 对应 Python 端 ExecutionState 的核心计数与终止信息部分；
 * 代理池、分布统计、AI 预填等子状态将在对应阶段补全。
 */
class ExecutionState(val config: ExecutionConfig) {
    var stopRequested: () -> Boolean = { false }


    // 运行时可挂载的反向填充解析器与心理测量计划提供者（由引擎注入）
    @Volatile var reverseFillResolver: com.surveycontroller.android.core.questions.ReverseFillResolver? = null
    @Volatile var samplePlanProvider: com.surveycontroller.android.core.questions.SamplePlanProvider? = null

    private val _curNum = AtomicInteger(0)
    private val _curFail = AtomicInteger(0)

    val curNum: Int get() = _curNum.get()
    val curFail: Int get() = _curFail.get()

    fun incrementSuccess(): Int = _curNum.incrementAndGet()
    fun incrementFail(): Int = _curFail.incrementAndGet()
    fun resetFail() = _curFail.set(0)

    @Volatile var terminalStopCategory: String = ""
        private set
    @Volatile var terminalFailureReason: String = ""
        private set
    @Volatile var terminalStopMessage: String = ""
        private set

    private val terminalLock = Any()

    fun markTerminalStop(
        category: String,
        failureReason: String = "",
        message: String = "",
        overwrite: Boolean = false,
    ) {
        val cat = category.trim()
        if (cat.isEmpty()) return
        synchronized(terminalLock) {
            if (terminalStopCategory.isNotEmpty() && !overwrite) return
            terminalStopCategory = cat
            terminalFailureReason = failureReason.trim()
            terminalStopMessage = message.trim()
        }
    }

    // 每个工作槽的状态文案，供 UI 观察
    private val _threadStatus = MutableStateFlow<Map<String, ThreadStatus>>(emptyMap())
    val threadStatus: StateFlow<Map<String, ThreadStatus>> = _threadStatus.asStateFlow()

    fun updateThreadStatus(threadName: String, step: String, running: Boolean) {
        if (threadName.isEmpty()) return
        _threadStatus.value = _threadStatus.value.toMutableMap().apply {
            this[threadName] = ThreadStatus(threadName, step, running)
        }
    }

    // ===== 分布统计运行时（供分布矫正使用）=====
    private val distLock = Any()
    private val committedCounts = HashMap<String, IntArray>()           // statKey -> counts
    private val pendingByThread = HashMap<String, MutableList<PendingChoice>>()

    /** 返回某统计键已提交的 (总数, 各选项计数)。复刻 snapshot_distribution_stats。 */
    fun snapshotDistribution(statKey: String, optionCount: Int): Pair<Int, List<Int>> = synchronized(distLock) {
        val counts = committedCounts[statKey]
        if (counts == null) return 0 to List(maxOf(0, optionCount)) { 0 }
        val total = counts.sum()
        total to counts.toList()
    }

    fun appendPendingDistribution(threadName: String, statKey: String, optionIndex: Int, optionCount: Int) {
        if (optionCount <= 0 || optionIndex < 0 || optionIndex >= optionCount) return
        synchronized(distLock) {
            pendingByThread.getOrPut(threadName) { mutableListOf() }
                .add(PendingChoice(statKey, optionIndex, optionCount))
        }
    }

    /** 提交成功后将该线程的待定分布并入已提交统计。 */
    fun commitPendingDistribution(threadName: String) = synchronized(distLock) {
        val pending = pendingByThread.remove(threadName) ?: return
        for (p in pending) {
            val arr = committedCounts.getOrPut(p.statKey) { IntArray(p.optionCount) }
            val resized = if (arr.size < p.optionCount) arr.copyOf(p.optionCount).also { committedCounts[p.statKey] = it } else arr
            if (p.optionIndex in resized.indices) resized[p.optionIndex]++
        }
    }

    fun discardPendingDistribution(threadName: String) {
        synchronized(distLock) { pendingByThread.remove(threadName) }
    }

    // ===== 免费 AI 批量预填（每线程 questionNum -> answers）=====
    private val aiPrefillLock = Any()
    private val freeAiPrefillByThread = HashMap<String, Map<Int, List<String>>>()

    fun setFreeAiPrefill(threadName: String, answersByQuestion: Map<Int, List<String>>) = synchronized(aiPrefillLock) {
        if (answersByQuestion.isEmpty()) freeAiPrefillByThread.remove(threadName)
        else freeAiPrefillByThread[threadName] = answersByQuestion
    }

    fun getFreeAiPrefill(threadName: String, questionNum: Int): List<String>? = synchronized(aiPrefillLock) {
        freeAiPrefillByThread[threadName]?.get(questionNum)
    }

    fun clearFreeAiPrefill(threadName: String) = synchronized(aiPrefillLock) {
        freeAiPrefillByThread.remove(threadName)
    }

    private data class PendingChoice(val statKey: String, val optionIndex: Int, val optionCount: Int)
}

data class ThreadStatus(
    val threadName: String,
    val step: String,
    val running: Boolean,
)
