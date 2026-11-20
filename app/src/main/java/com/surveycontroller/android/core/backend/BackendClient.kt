package com.surveycontroller.android.core.backend

import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.network.UserAgents
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 项目私有后端（api-wjx.hungrym0.com）的客户端接口。1:1 对接：
 * software/network/proxy/session、software/integrations/ai/free_api、submission_report。
 * 所有端点可通过 BackendEndpoints 覆盖（默认指向官方后端）。
 * 域名 v4.0.4 起由 .top 切换为 .com，须同步升级否则内置随机 IP 不可达。
 */
object BackendEndpoints {
    @Volatile var base = "https://api-wjx.hungrym0.com"
    val authTrial get() = "$base/api/auth/trial"

    /** v5.0.0 新增：Bearer 令牌校验 + 额度同步（GET）。 */
    val authSession get() = "$base/api/auth/session"
    val bonus get() = "$base/api/bonus"
    val cardRedeem get() = "$base/api/cards/redeem"
    val ipExtract get() = "$base/api/ip/extract"
    val submissionReport get() = "$base/api/submission/report"
    val aiFree get() = "$base/api/ai/free"
    val status get() = "$base/api/status"
    val ipUsage get() = "$base/ipzan/usage"
}

class BackendException(val detail: String, val statusCode: Int = 0, val retryAfterSeconds: Int = 0) :
    RuntimeException(detail)

data class ProxyLease(
    val host: String,
    val port: Int,
    val account: String,
    val password: String,
    val expireAt: String = "",
    val provider: String = "",
) {
    /** OkHttp 代理地址：host:port（含鉴权时 user:pass@host:port）。 */
    fun address(): String = if (account.isNotEmpty() && password.isNotEmpty())
        "$account:$password@$host:$port" else "$host:$port"
}

class BackendClient(
    private val http: HttpClient,
    private val deviceIdentity: DeviceIdentity,
    private val sessionStore: RandomIpSessionStore,
) {
    /**
     * 请求头。对齐官方 `_build_headers`：有令牌时带 `Authorization: Bearer`，
     * 始终带 `X-Device-ID`（激活卡密时靠它绑定设备）。
     */
    private suspend fun headers(accessToken: String? = null): Map<String, String> =
        UserAgents.DEFAULT_HEADERS.toMutableMap().apply {
            this["Content-Type"] = "application/json"
            this["X-Device-ID"] = deviceIdentity.get()
            val token = accessToken ?: runCatching { sessionStore.load().accessToken }.getOrDefault("")
            if (token.isNotEmpty()) this["Authorization"] = "Bearer $token"
        }

    private suspend fun postJson(
        url: String,
        body: JSONObject,
        timeoutSeconds: Long = 10,
        accessToken: String? = null,
    ): Pair<Int, JSONObject> {
        val resp = http.postBody(
            url,
            body.toString(),
            headers = headers(accessToken),
            timeoutSeconds = timeoutSeconds,
        )
        val json = try {
            JSONObject(resp.body.ifBlank { "{}" })
        } catch (e: Exception) {
            JSONObject()
        }
        return resp.statusCode to json
    }

    /** GET + JSON 解析。对齐官方 `_aget_json`（v5 起 /api/auth/session 为 GET）。 */
    private suspend fun getJson(url: String, timeoutSeconds: Long = 10): Pair<Int, JSONObject> {
        val resp = http.get(url, headers = headers(), timeoutSeconds = timeoutSeconds)
        val json = try {
            JSONObject(resp.body.ifBlank { "{}" })
        } catch (e: Exception) {
            JSONObject()
        }
        return resp.statusCode to json
    }

    private fun errorFrom(status: Int, json: JSONObject): BackendException {
        val detail = json.optString("detail").trim().ifEmpty { "http_$status" }
        val retry = json.optInt("retry_after_seconds", 0)
        return BackendException(detail, status, retry)
    }

    /** 应用服务端返回的额度字段；缺失时保留本地值（兼容尚未升级的服务端）。 */
    private fun applyQuota(json: JSONObject, base: RandomIpSession): RandomIpSession {
        val remaining = json.optDouble("remaining_quota", base.remainingQuota)
        val total = maxOf(json.optDouble("total_quota", base.totalQuota), remaining)
        val used = json.optDouble("used_quota", maxOf(0.0, total - remaining))
        return base.copy(remainingQuota = remaining, totalQuota = total, usedQuota = used)
    }

    /** 读取响应中的 Bearer 令牌（官方 access_token/token_type，兼容旧 token 字段）。 */
    private fun extractAccessToken(json: JSONObject, expected: String = ""): String {
        val token = json.optString("access_token").trim().ifBlank { json.optString("token").trim() }
        if (token.isEmpty()) return ""
        val tokenType = json.optString("token_type").trim()
        if (tokenType.isNotEmpty() && !tokenType.equals("Bearer", ignoreCase = true)) return ""
        if (expected.isNotEmpty() && token != expected) return ""
        return token
    }

    /** 由会话响应构造本地会话，保留已有令牌。 */
    private fun sessionFrom(json: JSONObject, base: RandomIpSession, token: String = ""): RandomIpSession {
        val userId = json.optInt("user_id", base.userId)
        if (userId <= 0) throw BackendException("invalid_response:user_id_invalid", 200)
        val withQuota = applyQuota(json, base.copy(userId = userId))
        return if (token.isNotEmpty()) withQuota.copy(accessToken = token) else withQuota
    }

    /**
     * 确保具备可用会话，对齐官方 v5 `ensure_authenticated_session_async`：
     * 1) 已有令牌 → 直接返回；
     * 2) 仅有 user_id 的旧会话 → POST /api/auth/trial 换取令牌；
     * 3) 全新设备 → POST /api/auth/trial 领取试用（服务端停止发放时会返回明确错误）。
     */
    suspend fun ensureSession(): RandomIpSession {
        val current = sessionStore.load()
        if (current.accessToken.isNotEmpty()) return current
        val body = if (current.userId > 0) JSONObject().put("user_id", current.userId) else JSONObject()
        val (status, json) = postJson(BackendEndpoints.authTrial, body, accessToken = "")
        if (status != 200) throw errorFrom(status, json)
        val session = sessionFrom(json, current, extractAccessToken(json))
        sessionStore.save(session)
        return session
    }

    /** 兼容旧调用点：语义等同于确保会话可用。 */
    suspend fun activateTrial(): RandomIpSession = ensureSession()

    /**
     * 同步额度：有令牌走 GET /api/auth/session（v5 新增），
     * 否则回落到旧的 trial 接口，保证老服务端仍可用。
     */
    suspend fun syncQuota(): RandomIpSession {
        val session = requireSession()
        val (status, json) = if (session.accessToken.isNotEmpty()) {
            getJson(BackendEndpoints.authSession)
        } else {
            postJson(
                BackendEndpoints.authTrial,
                JSONObject().put("user_id", session.userId),
                accessToken = "",
            )
        }
        if (status == 401 || status == 403) {
            // 令牌失效：清空后重新换取一次，对齐官方 invalid_access_token 处理
            sessionStore.save(session.copy(accessToken = ""))
            return ensureSession()
        }
        if (status != 200) throw errorFrom(status, json)
        val updated = sessionFrom(json, session, extractAccessToken(json))
        sessionStore.save(updated)
        return updated
    }

    private suspend fun requireSession(): RandomIpSession {
        val s = sessionStore.load()
        if (!s.authenticated) throw BackendException("not_authenticated")
        return s
    }

    /** 需要 Bearer 的接口：旧会话先自动换一次令牌，失败则抛出可读错误。 */
    private suspend fun requireReadySession(): RandomIpSession {
        val s = requireSession()
        return if (s.ready) s else ensureSession()
    }

    /** 提取代理 IP。复刻 extract_proxy_async：单个或批量。 */
    suspend fun extractProxies(
        minute: Int,
        pool: String,
        area: String?,
        num: Int = 1,
        upstream: String = "default",
    ): List<ProxyLease> {
        val session = requireReadySession()
        val body = JSONObject()
            .put("user_id", session.userId)
            .put("minute", minute)
            .put("pool", pool.trim())
        upstream.trim().lowercase().takeIf { it.isNotEmpty() }?.let { body.put("upstream", it) }
        val requestNum = maxOf(1, num)
        if (requestNum > 1) body.put("num", requestNum)
        area?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("area", it) }

        val timeout = minOf(60.0, 10.0 + (requestNum - 1) * 2.0).toLong()
        val (status, json) = postJson(BackendEndpoints.ipExtract, body, timeout)
        if (status != 200) throw errorFrom(status, json)

        val leases = mutableListOf<ProxyLease>()
        val items = json.optJSONArray("items")
        if (requestNum > 1 && items != null) {
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let { parseLease(it)?.let(leases::add) }
            }
        } else {
            parseLease(json)?.let(leases::add)
        }
        // 更新额度
        sessionStore.save(applyQuota(json, session))
        if (leases.isEmpty()) throw BackendException("invalid_response")
        return leases
    }

    private fun parseLease(d: JSONObject): ProxyLease? {
        val host = d.optString("host").trim()
        val port = d.optInt("port", 0)
        val account = d.optString("account").trim()
        val password = d.optString("password").trim()
        if (host.isEmpty() || port <= 0 || account.isEmpty() || password.isEmpty()) return null
        return ProxyLease(host, port, account, password, d.optString("expire_at").trim(), d.optString("provider").trim().lowercase())
    }

    /** 领取彩蛋奖励。复刻 claim_easter_egg_bonus_async。 */
    suspend fun claimBonus(): RandomIpSession {
        val session = requireSession()
        val (status, json) = postJson(
            BackendEndpoints.bonus,
            JSONObject().put("user_id", session.userId).put("bonus_code", "fuck-you-hacker"),
        )
        if (status != 200) throw errorFrom(status, json)
        val updated = applyQuota(json, session)
        sessionStore.save(updated)
        return updated
    }

    /**
     * 卡密兑换 / 激活。对齐 v5 `redeem_card_async`：
     * - 尚未持有令牌时属于"激活"：客户端生成 64 位 hex `activation_token` 一并提交，
     *   服务端回显 `access_token`（必须与之相等）与 `token_type=Bearer`；
     * - 已有令牌时属于"充值"：只提交卡密，用 Bearer 鉴权。
     */
    suspend fun redeemCard(cardCode: String): RandomIpSession {
        val session = sessionStore.load()
        val activating = session.accessToken.isEmpty()
        val activationToken = if (activating) newActivationToken() else ""
        val body = JSONObject().put("card_code", cardCode.trim())
        if (activating) {
            body.put("activation_token", activationToken)
            if (session.userId > 0) body.put("user_id", session.userId)
        }
        val (status, json) = postJson(
            BackendEndpoints.cardRedeem,
            body,
            accessToken = if (activating) "" else null,
        )
        if (status != 200) throw errorFrom(status, json)
        if (activating) {
            val token = extractAccessToken(json, expected = activationToken)
            // 服务端若返回了 access_token，就必须与本地 activation_token 一致（对齐官方校验）
            if (token.isEmpty() && json.optString("access_token").isNotEmpty()) {
                throw BackendException("invalid_response:access_token", status)
            }
            // 未返回 access_token 视为旧服务端，按无令牌会话继续使用
            val updated = sessionFrom(json, session, token)
            sessionStore.save(updated)
            return updated
        }
        val updated = applyQuota(json, session)
        sessionStore.save(updated)
        return updated
    }

    /** 生成 64 位十六进制激活令牌，对齐官方 `secrets.token_hex(32)`。 */
    private fun newActivationToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 后端在线状态。复刻 get_status。 */
    suspend fun status(): Pair<Boolean, String> {
        val resp = http.get(BackendEndpoints.status, headers = headers(), timeoutSeconds = 5)
        val json = try { JSONObject(resp.body.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
        val online = json.optBoolean("online", false)
        val message = json.optString("message").ifBlank { if (online) "系统正常运行中" else "系统当前不在线" }
        return online to message
    }

    /** 提交结果上报。复刻 report_submission_result_async。 */
    suspend fun reportSubmission(surveyUrl: String, result: String, proxyProvider: String, clientVersion: String): Boolean {
        val session = sessionStore.load()
        if (!session.authenticated) return false
        val body = JSONObject()
            .put("user_id", session.userId)
            .put("survey_url", surveyUrl.trim())
            .put("result", result.trim().lowercase())
            .put("proxy_provider", proxyProvider)
            .put("client_version", clientVersion)
        return try {
            val (status, _) = postJson(BackendEndpoints.submissionReport, body)
            status == 200
        } catch (e: Exception) {
            false
        }
    }

    /** 免费 AI 填空（单题）。复刻 call_free_ai_api_async。身份缺失自动领取试用。 */
    suspend fun freeAiAnswer(questionContent: String, questionType: String, blankCount: Int?, systemPrompt: String?): List<String> {
        var session = sessionStore.load()
        if (!session.ready) session = ensureSession()
        val body = JSONObject()
            .put("user_id", session.userId)
            .put("question_type", questionType)
            .put("question_content", questionContent)
        systemPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("system_prompt", it) }
        if (questionType == "multi_fill_blank") body.put("blank_count", blankCount ?: 0)
        val (status, json) = postJson(BackendEndpoints.aiFree, body, 60)
        if (status != 200) throw errorFrom(status, json)
        val raw = json.optJSONArray("answers") ?: throw BackendException("ai_empty_response", status)
        val answers = (0 until raw.length()).mapNotNull { raw.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
        if (answers.isEmpty()) throw BackendException("ai_empty_response", status)
        return answers
    }

    /** 免费 AI 批量填空项。 */
    data class FreeAiBatchItem(val itemId: String, val questionType: String, val content: String, val blankCount: Int?)

    /**
     * 免费 AI 批量填空：提交任务并轮询直至完成。复刻 free_api.py 的 batch/poll 链路。
     * 返回 itemId -> answers。失败项不返回。
     */
    suspend fun freeAiBatch(items: List<FreeAiBatchItem>, systemPrompt: String?): Map<String, List<String>> {
        if (items.isEmpty()) return emptyMap()
        var session = sessionStore.load()
        if (!session.ready) session = ensureSession()
        val completed = HashMap<String, List<String>>()
        // 单任务最多 64 题，分块提交
        items.chunked(64).forEach { chunk ->
            runCatching { submitAndPollBatch(session.userId, chunk, systemPrompt, completed) }
        }
        return completed
    }

    private suspend fun submitAndPollBatch(
        userId: Int,
        chunk: List<FreeAiBatchItem>,
        systemPrompt: String?,
        completed: HashMap<String, List<String>>,
    ) {
        val payloadItems = JSONArray()
        for (item in chunk) {
            val o = JSONObject().put("item_id", item.itemId).put("question_type", item.questionType).put("question_content", item.content)
            if (item.questionType == "multi_fill_blank") o.put("blank_count", item.blankCount ?: 0)
            payloadItems.put(o)
        }
        val body = JSONObject().put("user_id", userId).put("items", payloadItems)
        systemPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("system_prompt", it) }
        val (submitStatus, submitJson) = postJson("${BackendEndpoints.aiFree}/batch", body, 60)
        if (submitStatus != 200 && submitStatus != 202) throw errorFrom(submitStatus, submitJson)
        val taskId = submitJson.optString("task_id").trim()
        if (taskId.isEmpty()) throw BackendException("missing task_id", submitStatus)

        // 轮询直至终态（最多 ~45s）
        val deadline = System.currentTimeMillis() + 45_000
        var pollAfter = submitJson.optInt("poll_after_ms", 1000).coerceIn(200, 3000)
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(pollAfter.toLong())
            val resp = http.get("${BackendEndpoints.aiFree}/tasks/$taskId", headers = headers(), timeoutSeconds = 60)
            val json = try { JSONObject(resp.body.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
            pollAfter = json.optInt("poll_after_ms", pollAfter).coerceIn(200, 3000)
            json.optJSONArray("items")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    if (it.optString("status").lowercase() == "completed") {
                        val a = it.optJSONArray("answers")
                        if (a != null) {
                            val list = (0 until a.length()).mapNotNull { k -> a.optString(k).trim().takeIf { s -> s.isNotEmpty() } }
                            if (list.isNotEmpty()) completed[it.optString("item_id")] = list
                        }
                    }
                }
            }
            val status = json.optString("status").lowercase()
            if (status in listOf("completed", "partial", "failed", "expired")) break
        }
    }

    suspend fun currentSession(): RandomIpSession = sessionStore.load()

    /** 每日 IP 提取记录 + IP 池剩余数量。复刻 io/reports/ip_usage_log.get_usage_summary。 */
    suspend fun ipUsageSummary(): IpUsageSummary {
        val resp = http.get(BackendEndpoints.ipUsage, headers = headers(), timeoutSeconds = 10)
        if (resp.statusCode != 200) throw BackendException("http_${resp.statusCode}", resp.statusCode)
        val body = resp.body.ifBlank { "{}" }
        val root: Any = try {
            val t = body.trimStart()
            if (t.startsWith("[")) JSONArray(body) else JSONObject(body)
        } catch (e: Exception) {
            JSONObject()
        }
        val records = extractRecords(root).map {
            IpUsageRecord(it.optString("label").trim(), toIntOrZero(it.opt("total")))
        }.filter { it.label.isNotEmpty() }
        return IpUsageSummary(records, extractRemainingIp(root))
    }

    private fun toIntOrZero(raw: Any?): Int =
        (raw as? Number)?.toInt() ?: raw?.toString()?.trim()?.toDoubleOrNull()?.toInt() ?: 0

    private fun toIntOrNull(raw: Any?): Int? =
        (raw as? Number)?.toInt() ?: raw?.toString()?.trim()?.toDoubleOrNull()?.toInt()

    /** 递归提取记录数组（对齐桌面端 _extract_records 的容错逻辑）。 */
    private fun extractRecords(payload: Any?): List<JSONObject> {
        when (payload) {
            is JSONArray -> {
                val direct = (0 until payload.length()).mapNotNull { payload.optJSONObject(it) }
                if (direct.isNotEmpty() && direct.all { it.has("label") || it.has("total") }) return direct
                direct.forEach { val nested = extractRecords(it); if (nested.isNotEmpty()) return nested }
                return emptyList()
            }
            is JSONObject -> {
                for (key in listOf("records", "history", "items", "list")) {
                    val v = payload.optJSONArray(key)
                    if (v != null) {
                        val list = (0 until v.length()).mapNotNull { v.optJSONObject(it) }
                        if (list.isNotEmpty()) return list
                    }
                }
                val keys = payload.keys()
                while (keys.hasNext()) {
                    val nested = extractRecords(payload.opt(keys.next()))
                    if (nested.isNotEmpty()) return nested
                }
                return emptyList()
            }
            else -> return emptyList()
        }
    }

    /** 递归提取 IP 池剩余数量。 */
    private fun extractRemainingIp(payload: Any?): Int? {
        when (payload) {
            is JSONObject -> {
                for (key in listOf("remaining_ip", "remainingIp", "ip_remaining", "remaining")) {
                    if (payload.has(key)) toIntOrNull(payload.opt(key))?.let { return maxOf(0, it) }
                }
                val keys = payload.keys()
                while (keys.hasNext()) {
                    extractRemainingIp(payload.opt(keys.next()))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until payload.length()) extractRemainingIp(payload.opt(i))?.let { return it }
            }
        }
        return null
    }
}

/** 单日 IP 提取记录。label 形如 yyyy-MM-dd。 */
data class IpUsageRecord(val label: String, val total: Int)

/** IP 用量汇总。 */
data class IpUsageSummary(val records: List<IpUsageRecord>, val remainingIp: Int?)

/**
 * 把后端错误细化成可操作的中文提示，对齐官方 v5 `format_random_ip_error`。
 * 重点覆盖 v5 移除免费试用后最常见的两种情况（领取试用失败 / 令牌失效）。
 */
fun backendErrorMessage(error: Throwable): String {
    val detail = (error as? BackendException)?.detail?.trim().orEmpty()
    if (detail.isEmpty()) return error.message ?: "请求失败，请稍后重试"
    val lower = detail.lowercase()
    return when {
        lower == "not_authenticated" || lower.contains("not_authenticated") ->
            "尚未激活随机 IP 账号，请先用额度卡密激活"
        lower.contains("invalid_access_token") || lower == "unauthorized" ->
            "登录状态已失效，请重新同步额度或重新激活"
        lower.contains("trial") ->
            "服务端已停止发放免费试用，请使用额度卡密激活随机 IP"
        lower.contains("invalid_redeem_card_code") ->
            "卡密格式不正确，应为 16 位字母或数字"
        lower.contains("quota_exhausted") ->
            "随机 IP 额度已用完，请兑换卡密后重试"
        lower.contains("session_persist_failed") ->
            "登录凭据保存失败，请重试；若持续失败请重启应用"
        lower.startsWith("http_5") ->
            "服务端暂时不可用（${detail.removePrefix("http_")}）"
        lower.startsWith("network_error") ->
            "网络请求失败，请检查网络后重试"
        lower == "invalid_response:access_token" ->
            "服务端返回的登录凭据校验失败，请重试"
        else -> detail
    }
}
