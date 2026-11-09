package com.aicode.quotasentry.core

import kotlin.math.abs
import kotlin.math.max

/**
 * 额度窗口的纯计算逻辑（不依赖 Android，方便单元测试）。
 *
 * 优先用官方数据；官方接口不可用（常见于 Gemini，以及部分账号）时退化为
 * 本地「滚动窗口」估算：窗口内每发一条消息计一次，窗口到期自动归零。
 */
object QuotaMath {

    const val DEFAULT_WINDOW_MILLIS: Long = 5L * 60L * 60L * 1000L

    /** 本地估算窗口内还剩多少条（会先做窗口滚动，不修改入参）。 */
    fun effectiveSent(quota: QuotaState, now: Long, windowMillis: Long): Int {
        if (quota.localWindowStart <= 0L) return 0
        val elapsed = now - quota.localWindowStart
        return if (elapsed >= windowMillis || elapsed < 0L) 0 else quota.localSent
    }

    /** 窗口到期后把本地计数归零并开新窗口。 */
    fun rollWindow(quota: QuotaState, now: Long, windowMillis: Long): QuotaState {
        if (quota.localWindowStart <= 0L) {
            return quota.copy(localWindowStart = now, localSent = 0)
        }
        val elapsed = now - quota.localWindowStart
        return if (elapsed >= windowMillis || elapsed < 0L) {
            quota.copy(localWindowStart = now, localSent = 0)
        } else {
            quota
        }
    }

    /** 记录一次成功发送：窗口滚动 + 计数 +1。 */
    fun registerSend(quota: QuotaState, now: Long, windowMillis: Long, limit: Int): QuotaState {
        val rolled = rollWindow(quota, now, windowMillis)
        return rolled.copy(
            localSent = (rolled.localSent + 1).coerceAtMost(max(limit, 1) * 4),
            localLimit = limit,
            lastSendAt = now,
            localWindowStart = if (rolled.localWindowStart <= 0L) now else rolled.localWindowStart
        )
    }

    /** 合并一次官方额度结果；官方数据会覆盖本地来源标记，但保留本地计数。 */
    fun applyPatch(quota: QuotaState, patch: QuotaPatch, now: Long): QuotaState {
        val window = (patch.officialWindowMillis ?: quota.officialWindowMillis ?: DEFAULT_WINDOW_MILLIS)
            .coerceIn(60_000L, 30L * 24 * 3600_000L)
        return quota.copy(
            officialUsedFraction = patch.officialUsedFraction?.coerceIn(0f, 1f),
            officialWindowMillis = window,
            officialResetAt = patch.officialResetAt,
            officialDetail = patch.officialDetail.ifBlank { quota.officialDetail },
            source = if (patch.officialUsedFraction != null || patch.officialResetAt != null) {
                QuotaSource.OFFICIAL
            } else {
                quota.source
            },
            fetchedAt = now
        )
    }

    /** 派生展示视图。 */
    fun view(quota: QuotaState, settings: Settings, now: Long): QuotaView {
        val window = settings.windowMillis()
        val limit = quota.localLimit.takeIf { it > 0 } ?: settings.limitFor(quota.provider)

        // 1) 官方窗口。官方重置时刻已经过去很久时视为数据过期（服务没刷新成功），
        //    不能继续拿旧百分比吓用户，退化为本地估算并标注「待刷新」。
        val officialFresh = quota.officialUsedFraction != null &&
            (quota.officialResetAt == null || now <= quota.officialResetAt + 2 * 60_000L)
        if (quota.officialUsedFraction != null && officialFresh) {
            val used = quota.officialUsedFraction.coerceIn(0f, 1f)
            val resetAt = quota.officialResetAt ?: quota.localWindowStart.takeIf { it > 0 }?.plus(
                quota.officialWindowMillis ?: window
            )
            val resetValid = resetAt != null && resetAt > now
            return QuotaView(
                usedFraction = used,
                remainingFraction = 1f - used,
                remainingCount = null,
                totalCount = null,
                resetAt = if (resetValid) resetAt else null,
                source = QuotaSource.OFFICIAL,
                sourceLabel = "官方",
                windowMillis = quota.officialWindowMillis ?: window,
                low = used >= 0.85f,
                detail = quota.officialDetail
            )
        }

        // 2) 本地估算（官方数据不可用 / 已过期）
        val staleOfficial = quota.officialUsedFraction != null
        val sent = effectiveSent(quota, now, window)
        val start = if (quota.localWindowStart > 0L && now - quota.localWindowStart < window) {
            quota.localWindowStart
        } else {
            now
        }
        val remaining = (limit - sent).coerceAtLeast(0)
        val resetAt = if (sent > 0) start + window else null
        val fraction = if (limit <= 0) 0f else (sent.toFloat() / limit).coerceIn(0f, 1f)
        return QuotaView(
            usedFraction = fraction,
            remainingFraction = 1f - fraction,
            remainingCount = remaining,
            totalCount = limit,
            resetAt = resetAt?.takeIf { it > now },
            source = QuotaSource.ESTIMATED,
            sourceLabel = if (staleOfficial) "待刷新" else "本地估算",
            windowMillis = window,
            low = remaining <= max(1, (limit * 0.2f).toInt()),
            detail = when {
                staleOfficial -> "官方窗口已重置，等下一次刷新"
                sent == 0 -> "本窗口还没发送"
                else -> "本窗口已发 $sent 条"
            }
        )
    }

    /**
     * 是否“即将重置”：用于跟随窗口的计划，在官方窗口重置前后触发。
     */
    fun resetDue(quota: QuotaState, now: Long, leadMillis: Long = 5 * 60_000L): Boolean {
        val resetAt = quota.officialResetAt ?: return false
        val delta = resetAt - now
        return delta in -leadMillis..leadMillis
    }

    /** 剩余比例（给环形进度用，0..1，未知时按 1 处理）。 */
    fun remainingOf(view: QuotaView): Float = view.remainingFraction.coerceIn(0f, 1f)

    /** 供调试 / 单测：判断两个时间戳是否落在同一窗口。 */
    fun sameWindow(a: Long, b: Long, windowMillis: Long): Boolean =
        a > 0 && b > 0 && abs(a - b) < windowMillis
}
