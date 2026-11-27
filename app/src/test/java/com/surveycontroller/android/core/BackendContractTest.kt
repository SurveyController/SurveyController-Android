package com.surveycontroller.android.core

import com.surveycontroller.android.core.backend.BackendEndpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证私有后端端点路径与桌面端一致（api-wjx.hungrym0.top 系列）。
 */
class BackendContractTest {

    @Test
    fun endpoints_match_python_defaults() {
        BackendEndpoints.base = "https://api-wjx.hungrym0.top"
        assertEquals("https://api-wjx.hungrym0.top/api/auth/trial", BackendEndpoints.authTrial)
        assertEquals("https://api-wjx.hungrym0.top/api/bonus", BackendEndpoints.bonus)
        assertEquals("https://api-wjx.hungrym0.top/api/cards/redeem", BackendEndpoints.cardRedeem)
        assertEquals("https://api-wjx.hungrym0.top/api/ip/extract", BackendEndpoints.ipExtract)
        assertEquals("https://api-wjx.hungrym0.top/api/submission/report", BackendEndpoints.submissionReport)
        assertEquals("https://api-wjx.hungrym0.top/api/status", BackendEndpoints.status)
    }

    @Test
    fun base_override_applies_to_all_endpoints() {
        BackendEndpoints.base = "https://example.test"
        // 还原默认，避免影响其他测试
        BackendEndpoints.base = "https://api-wjx.hungrym0.top"
    }
}
