package com.surveycontroller.android.core

import com.surveycontroller.android.core.backend.ProxyLease
import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.network.BackendProxyPool
import com.surveycontroller.android.core.network.getProxyRequiredTtlSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 对标官方 test_proxy_ttl_helpers 与
 * test_merge_prefetched_proxy_leases_keeps_future_proxy_without_ttl_floor。
 */
class BackendProxyPoolTest {
    @Test
    fun http_proxy_min_remaining_ttl_is_zero_for_pure_http_providers() {
        assertEquals(0, BackendProxyPool.HTTP_PROXY_MIN_REMAINING_TTL_SECONDS)
        assertEquals(0, getProxyRequiredTtlSeconds(ExecutionConfig(surveyProvider = "wjx")))
        assertEquals(0, getProxyRequiredTtlSeconds(ExecutionConfig(surveyProvider = "qq")))
        assertEquals(0, getProxyRequiredTtlSeconds(ExecutionConfig(surveyProvider = "credamo")))
    }

    @Test
    fun future_lease_is_kept_and_expired_lease_is_dropped() {
        val soon = lease("1.1.1.1", Instant.now().plusSeconds(5))
        val expired = lease("2.2.2.2", Instant.now().minusSeconds(1))

        assertTrue(BackendProxyPool.proxyLeaseHasSufficientTtl(soon, 0))
        assertFalse(BackendProxyPool.proxyLeaseHasSufficientTtl(expired, 0))
    }

    private fun lease(host: String, expireAt: Instant) = ProxyLease(
        host = host,
        port = 8000,
        account = "",
        password = "",
        expireAt = expireAt.toString(),
    )
}
