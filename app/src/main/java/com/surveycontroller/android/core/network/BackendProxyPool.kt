package com.surveycontroller.android.core.network

import com.surveycontroller.android.core.backend.BackendClient
import com.surveycontroller.android.core.backend.ProxyLease
import com.surveycontroller.android.core.engine.ProxyProvider
import com.surveycontroller.android.core.model.ExecutionConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.ceil

/**
 * 默认/限时福利代理源的 IP 池：走项目后端 /api/ip/extract。
 * 复刻 software/network/proxy/api/provider.py 的官方源提取链路。
 */
class BackendProxyPool(
    private val backend: BackendClient,
    private val source: String, // "default" / "benefit"
) : ProxyProvider {
    private val mutex = Mutex()
    private val available = ArrayDeque<ProxyLease>()

    override suspend fun acquire(config: ExecutionConfig): String? {
        val requiredTtl = getProxyRequiredTtlSeconds(config)
        // 1. 先从池中取；TTL 不足的丢弃继续取下一个（v4.0.2 / #87）
        mutex.withLock {
            while (available.isNotEmpty()) {
                val lease = available.removeFirst()
                if (proxyLeaseHasSufficientTtl(lease, requiredTtl)) {
                    return lease.address()
                }
                // 过期：丢弃，继续取下一个
            }
        }
        // 2. 池空，拉取新批次
        val leases = fetch(config, expected = maxOf(1, config.numThreads))
        if (leases.isEmpty()) return null
        // 3. 第一个 TTL 充足的 lease 立即使用；其余仅 poolable（有 expireAt）的入池
        mutex.withLock {
            var selected: ProxyLease? = null
            for (lease in leases) {
                if (!proxyLeaseHasSufficientTtl(lease, requiredTtl)) continue
                if (selected == null) {
                    selected = lease
                    continue
                }
                // 无 expireAt 的 lease 仅允许立即使用，不入池（v4.0.2 / #87）
                if (lease.expireAt.isNotBlank()) {
                    available.addLast(lease)
                }
            }
            return selected?.address()
        }
    }

    private suspend fun fetch(config: ExecutionConfig, expected: Int): List<ProxyLease> {
        val isBenefit = source == "benefit"
        val upstream = if (isBenefit) "idiot" else "default"
        // 代理占用分钟：覆盖作答时长 + 缓冲；福利源固定 1 分钟
        val minute = if (isBenefit) 1 else {
            val maxDurationSec = config.answerDurationRangeSeconds.last.coerceAtLeast(60)
            maxOf(1, ceil(maxDurationSec / 60.0).toInt() + 1)
        }
        return try {
            backend.extractProxies(
                minute = minute,
                pool = "quality",
                area = config.proxyAreaCode,
                num = expected,
                upstream = upstream,
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        /**
         * 纯 HTTP 链路不设置预留时长门槛；仍由 expireAt 判断代理是否已经过期。
         * 对齐 HTTP_PROXY_MIN_REMAINING_TTL_SECONDS（官方 aca7b90，#103）。
         */
        const val HTTP_PROXY_MIN_REMAINING_TTL_SECONDS = 0

        /**
         * 校验 lease 剩余 TTL 是否充足。复刻 software/network/proxy/pool/pool.py:proxy_lease_has_sufficient_ttl。
         * 无 expireAt（或解析失败）时返回 True，仅允许立即使用、不入池。
         */
        fun proxyLeaseHasSufficientTtl(lease: ProxyLease?, requiredTtlSeconds: Int): Boolean {
            if (lease == null) return false
            val expireTs = parseExpireAtToEpochSeconds(lease.expireAt)
            if (expireTs <= 0L) return true
            val now = System.currentTimeMillis() / 1000
            return (expireTs - now) >= maxOf(0, requiredTtlSeconds.toLong())
        }

        /** 解析 ISO expire_at 为 epoch 秒。空/解析失败返回 0。复刻 _parse_expire_at_to_ts。 */
        private fun parseExpireAtToEpochSeconds(expireAt: String): Long {
            val text = expireAt.trim()
            if (text.isEmpty()) return 0L
            // 兼容 "YYYY-MM-DD HH:MM:SS" → "YYYY-MM-DDTHH:MM:SS"
            val normalized = if (text.length > 10 && text[10] == ' ') {
                text.substring(0, 10) + "T" + text.substring(11)
            } else {
                text
            }
            return try {
                try {
                    OffsetDateTime.parse(normalized).toEpochSecond()
                } catch (e: Exception) {
                    try {
                        Instant.parse(normalized).epochSecond
                    } catch (e2: Exception) {
                        // 无时区信息则按 UTC 处理
                        LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC).epochSecond
                    }
                }
            } catch (e: Exception) {
                0L
            }
        }
    }
}

/**
 * 计算代理所需最小 TTL（秒）。复刻 software/network/proxy/pool/pool.py:get_proxy_required_ttl_seconds。
 * WJX/QQ/CREDAMO 返回最低阈值；其余按作答时长上限 + 缓冲（PROXY_TTL_GRACE_SECONDS=20）估算。
 */
fun getProxyRequiredTtlSeconds(config: ExecutionConfig): Int {
    val provider = config.surveyProvider.trim().lowercase()
    if (provider in setOf("wjx", "qq", "credamo")) {
        return BackendProxyPool.HTTP_PROXY_MIN_REMAINING_TTL_SECONDS
    }
    val maxSeconds = config.answerDurationRangeSeconds.last.coerceAtLeast(0)
    return maxOf(0, maxSeconds) + 20
}
