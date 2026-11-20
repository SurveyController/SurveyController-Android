package com.surveycontroller.android.core.backend

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.ipSessionStore by preferencesDataStore(name = "random_ip_session")

/**
 * 随机IP账号会话（用户ID + Bearer 令牌 + 额度）。对应官方 v5 `RandomIPSession`。
 *
 * v5.0.0 起后端改为 `Authorization: Bearer <access_token>` 鉴权：
 * 只有同时具备 user_id 与 access_token 的会话才算就绪（`ready`），
 * 仅剩 user_id 的旧会话为 `legacy`，需要走一次令牌交换。
 */
data class RandomIpSession(
    val userId: Int = 0,
    val accessToken: String = "",
    val remainingQuota: Double = 0.0,
    val totalQuota: Double = 0.0,
    val usedQuota: Double = 0.0,
) {
    /** 具备账号身份（兼容仅 user_id 的旧会话）。 */
    val authenticated: Boolean get() = userId > 0

    /** 具备完整凭据，可直接调用需要鉴权的接口。 */
    val ready: Boolean get() = userId > 0 && accessToken.isNotEmpty()

    /** 会话状态：ready / legacy / anonymous，对齐官方 _session_state_name。 */
    val state: String
        get() = when {
            ready -> "ready"
            authenticated -> "legacy"
            else -> "anonymous"
        }
}

class RandomIpSessionStore(private val context: Context) {
    private val kUser = intPreferencesKey("user_id")
    private val kToken = stringPreferencesKey("access_token")
    private val kRemain = doublePreferencesKey("remaining_quota")
    private val kTotal = doublePreferencesKey("total_quota")
    private val kUsed = doublePreferencesKey("used_quota")

    suspend fun load(): RandomIpSession {
        val p = context.ipSessionStore.data.first()
        return RandomIpSession(
            userId = p[kUser] ?: 0,
            accessToken = p[kToken] ?: "",
            remainingQuota = p[kRemain] ?: 0.0,
            totalQuota = p[kTotal] ?: 0.0,
            usedQuota = p[kUsed] ?: 0.0,
        )
    }

    suspend fun save(session: RandomIpSession) {
        context.ipSessionStore.edit {
            it[kUser] = session.userId
            it[kToken] = session.accessToken
            it[kRemain] = session.remainingQuota
            it[kTotal] = session.totalQuota
            it[kUsed] = session.usedQuota
        }
    }

    suspend fun clear() {
        context.ipSessionStore.edit { it.clear() }
    }
}
