package com.aicode.quotasentry.service

import android.content.Context
import android.os.PowerManager
import com.aicode.quotasentry.core.Account
import com.aicode.quotasentry.core.EventKind
import com.aicode.quotasentry.core.QuotaFetch
import com.aicode.quotasentry.core.QuotaMath
import com.aicode.quotasentry.core.SendOutcome
import com.aicode.quotasentry.core.Store
import com.aicode.quotasentry.data.Providers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/**
 * 网络期间的短时 WakeLock。
 *
 * 只在真正发请求时持有，超时 2 分钟自动释放；绝不长时间持锁。
 */
internal object WakeLocks {

    private const val TAG = "QuotaSentry:net"
    private const val TIMEOUT_MILLIS = 2L * 60_000L

    fun acquire(context: Context): PowerManager.WakeLock? {
        val manager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return null
        return runCatching {
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
                setReferenceCounted(false)
                acquire(TIMEOUT_MILLIS)
            }
        }.getOrNull()
    }

    fun release(lock: PowerManager.WakeLock?) {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }

    suspend fun <T> withLock(context: Context, block: suspend () -> T): T {
        val lock = acquire(context)
        return try {
            block()
        } finally {
            release(lock)
        }
    }
}

/**
 * 发送 / 额度刷新的执行引擎（多账号版）：服务与 UI 触发的一次性兜底共用。
 *
 * 职责：
 * - 按账号调用 ProviderClient（失败一律转成中文原因，不抛给调用方）；
 * - 发送成功后按账号 `QuotaMath.registerSend` + `Store.record`（事件文案带账号名）；
 * - 刷新成功后按账号 `QuotaMath.applyPatch`；
 * - 网络期间才拿 WakeLock。
 */
object SendEngine {

    data class Result(
        val ok: Boolean,
        val detail: String = "",
        val unauthorized: Boolean = false
    )

    /** 同一账号的刷新失败文案去重，避免每 15 分钟往时间线里塞同一条错误。 */
    private val lastRefreshError = ConcurrentHashMap<String, Pair<String, Long>>()
    private const val ERROR_DEDUPE_MILLIS: Long = 60L * 60_000L

    /** 给某个账号发一条保活消息。 */
    suspend fun send(context: Context, account: Account, message: String): Result =
        withContext(Dispatchers.IO) {
            val provider = account.provider
            if (!account.linked || !account.credentials.present) {
                return@withContext Result(
                    false,
                    "${AccountSupport.title(account)} 未连接，请先在设置里连接"
                )
            }
            if (!Providers.has(provider)) {
                return@withContext Result(false, "${provider.label} 接入尚未就绪，请稍后再试")
            }
            val title = AccountSupport.title(account)
            val settings = Store.settingsNow()
            val outcome = WakeLocks.withLock(context) {
                runCatching { Providers.of(provider).send(account, settings, message) }
                    .getOrElse { SendOutcome(ok = false, detail = readableError(it)) }
            }
            val now = System.currentTimeMillis()
            if (outcome.ok) {
                Store.updateQuotaById(account.id) { quota ->
                    QuotaMath.registerSend(
                        quota,
                        now,
                        settings.windowMillis(),
                        settings.limitFor(provider)
                    ).copy(lastSendOk = true, lastSendMessage = message)
                }
                Store.record(
                    provider = provider,
                    ok = true,
                    text = "$title 已发送「$message」",
                    detail = outcome.detail,
                    kind = EventKind.SEND,
                    at = now
                )
                Result(true, outcome.detail)
            } else {
                Store.updateQuotaById(account.id) {
                    it.copy(lastSendOk = false, lastSendMessage = message)
                }
                val channel = if (account.credentials.accessToken.isNotBlank()) "OAuth" else "网页"
                val detailText = "【$channel 通道】" + outcome.detail.ifBlank { "未知错误" }
                Store.record(
                    provider = provider,
                    ok = false,
                    text = "$title 发送失败",
                    detail = detailText,
                    kind = EventKind.SEND,
                    at = now
                )
                Result(false, detailText, outcome.unauthorized)
            }
        }

    /**
     * 刷新某个账号的官方额度。
     *
     * @param record 是否往时间线写一条事件（自动刷新时也记录，便于用户排查账号问题）
     */
    suspend fun refresh(context: Context, account: Account, record: Boolean = true): Result =
        withContext(Dispatchers.IO) {
            val provider = account.provider
            if (!account.linked || !account.credentials.present) {
                return@withContext Result(false, "${AccountSupport.title(account)} 未连接")
            }
            if (!Providers.has(provider)) {
                return@withContext Result(false, "${provider.label} 接入尚未就绪")
            }
            val title = AccountSupport.title(account)
            val settings = Store.settingsNow()
            val fetch = WakeLocks.withLock(context) {
                runCatching { Providers.of(provider).fetchQuota(account, settings) }
                    .getOrElse { QuotaFetch.Fail(readableError(it)) }
            }
            val now = System.currentTimeMillis()
            when (fetch) {
                is QuotaFetch.Ok -> {
                    Store.updateQuotaById(account.id) { quota ->
                        QuotaMath.applyPatch(quota, fetch.patch, now)
                    }
                    if (record) {
                        Store.record(
                            provider = provider,
                            ok = true,
                            text = "$title 官方额度已更新",
                            detail = fetch.patch.officialDetail,
                            kind = EventKind.REFRESH,
                            at = now
                        )
                    }
                    Result(true, fetch.patch.officialDetail)
                }

                is QuotaFetch.Unsupported -> {
                    // 该服务没有官方额度接口（例如 Gemini），走本地估算，不算失败
                    Result(true, fetch.message.ifBlank { "该服务无官方额度接口，使用本地估算" })
                }

                is QuotaFetch.Fail -> {
                    if (record) recordRefreshErrorOnce(account, title, fetch.message, now)
                    Result(false, fetch.message, fetch.unauthorized)
                }
            }
        }

    /** 刷新失败事件去重：同一账号同一文案 1 小时内只记录一次。 */
    private fun recordRefreshErrorOnce(account: Account, title: String, message: String, now: Long) {
        val text = message.ifBlank { "额度刷新失败" }
        val previous = lastRefreshError[account.id]
        if (previous != null && previous.first == text && now - previous.second < ERROR_DEDUPE_MILLIS) {
            return
        }
        lastRefreshError[account.id] = text to now
        Store.record(
            provider = account.provider,
            ok = false,
            text = "$title 额度刷新失败",
            detail = text,
            kind = EventKind.ERROR,
            at = now
        )
    }

    /** 把网络异常翻译成用户能操作的中文原因。 */
    internal fun readableError(t: Throwable): String = when (t) {
        is UnknownHostException -> "网络不通，请检查网络或代理设置"
        is SocketTimeoutException -> "连接超时，请检查网络或代理"
        is SSLException -> "HTTPS 连接失败，代理可能不支持"
        else -> t.message?.takeIf { it.isNotBlank() } ?: "未知错误（${t.javaClass.simpleName}）"
    }
}
