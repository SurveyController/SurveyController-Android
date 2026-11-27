package com.surveycontroller.android.core

import com.surveycontroller.android.core.engine.RunEngine
import com.surveycontroller.android.core.engine.RunStatus
import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.network.SurveySubmitTransportError
import com.surveycontroller.android.provider.SubmitResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class RunEngineTest {
    @Test
    fun retries_proxy_failure_without_exceeding_target() = runBlocking {
        val attempts = AtomicInteger()
        val engine = RunEngine(submit = { _, _, _, _, _ ->
            if (attempts.incrementAndGet() == 1) throw SurveySubmitTransportError.ProxyTunnel("tunnel failed")
            SubmitResult.Success
        })

        engine.start(config(target = 2, workers = 4), this)
        val progress = withTimeout(5000) { engine.progress.first { it.status == RunStatus.FINISHED } }
        assertEquals(3, attempts.get())
        assertEquals(2, progress.success)
        assertEquals(1, progress.failureReasons["代理连接失败"])
    }

    @Test
    fun stop_waits_for_in_flight_result_and_ignores_duplicate_start() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = RunEngine(submit = { _, _, _, _, _ ->
            entered.complete(Unit)
            release.await()
            SubmitResult.Success
        })
        engine.start(config(target = 2, workers = 1), this)
        withTimeout(5000) { entered.await() }
        engine.start(config(target = 3, workers = 1), this)
        val stopping = async { engine.stop() }
        withTimeout(5000) { engine.progress.first { it.status == RunStatus.STOPPING } }
        release.complete(Unit)
        withTimeout(5000) { stopping.await() }

        val final = engine.progress.value
        assertEquals(RunStatus.IDLE, final.status)
        assertEquals(1, final.success)
        assertEquals(2, final.target)
    }

    @Test
    fun unknown_result_stops_without_retrying() = runBlocking {
        val attempts = AtomicInteger()
        val engine = RunEngine(submit = { _, _, _, _, _ ->
            attempts.incrementAndGet()
            throw SurveySubmitTransportError.ResultUnknown("read timed out", IOException())
        })

        engine.start(config(target = 2, workers = 1), this)
        val progress = withTimeout(5000) { engine.progress.first { it.status == RunStatus.FAILED } }
        assertEquals(1, attempts.get())
        assertEquals(1, progress.failureReasons["提交结果不确定"])
    }

    private fun config(target: Int, workers: Int): ExecutionConfig = ExecutionConfig(
        url = "https://www.wjx.cn/vm/demo.aspx",
        targetNum = target,
        numThreads = workers,
        stopOnFailEnabled = false,
        reliabilityModeEnabled = false,
    )
}
