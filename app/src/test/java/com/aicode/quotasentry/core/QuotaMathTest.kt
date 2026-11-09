package com.aicode.quotasentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class QuotaMathTest {

    private val hour = TimeUnit.HOURS.toMillis(1)
    private val base = 1_700_000_000_000L
    private fun settings(windowHours: Int = 5) = Settings(quotaWindowHours = windowHours, claudeLimit = 45)

    private fun estimated(sent: Int, start: Long, limit: Int = 45) = QuotaState(
        provider = ProviderId.CLAUDE,
        source = QuotaSource.ESTIMATED,
        localWindowStart = start,
        localSent = sent,
        localLimit = limit
    )

    @Test
    fun `official used fraction maps to remaining`() {
        val quota = QuotaState(
            provider = ProviderId.CLAUDE,
            officialUsedFraction = 0.3f,
            officialResetAt = base + hour,
            source = QuotaSource.OFFICIAL
        )
        val view = QuotaMath.view(quota, settings(), base)
        assertEquals(0.7f, view.remainingFraction, 0.0001f)
        assertEquals(QuotaSource.OFFICIAL, view.source)
        assertEquals(base + hour, view.resetAt)
        assertNull(view.remainingCount)
        assertFalse(view.low)
    }

    @Test
    fun `stale official data falls back to local estimate`() {
        val quota = QuotaState(
            provider = ProviderId.CHATGPT,
            officialUsedFraction = 0.1f,
            officialResetAt = base - hour,
            source = QuotaSource.OFFICIAL
        )
        val view = QuotaMath.view(quota, settings(), base)
        assertNull(view.resetAt)
        assertEquals(QuotaSource.ESTIMATED, view.source)
        assertEquals("待刷新", view.sourceLabel)
        assertEquals(45, view.remainingCount)
    }

    @Test
    fun `local window counts sends and derives reset time`() {
        var quota = estimated(sent = 0, start = base)
        quota = QuotaMath.registerSend(quota, base + 1000, 5 * hour, 45)
        quota = QuotaMath.registerSend(quota, base + 2000, 5 * hour, 45)
        val view = QuotaMath.view(quota, settings(), base + 3000)
        assertEquals(2, quota.localSent)
        assertEquals(43, view.remainingCount)
        assertEquals(45, view.totalCount)
        assertEquals(base + 5 * hour, view.resetAt)
        assertEquals(QuotaSource.ESTIMATED, view.source)
    }

    @Test
    fun `window rolls over after five hours`() {
        val quota = estimated(sent = 12, start = base)
        val rolled = QuotaMath.rollWindow(quota, base + 5 * hour + 1, 5 * hour)
        assertEquals(0, rolled.localSent)
        assertEquals(base + 5 * hour + 1, rolled.localWindowStart)
        val view = QuotaMath.view(quota, settings(), base + 5 * hour + 1)
        assertEquals(45, view.remainingCount)
        assertNull(view.resetAt)
    }

    @Test
    fun `low flag when nearly exhausted`() {
        val view = QuotaMath.view(estimated(sent = 40, start = base), settings(), base + 1000)
        assertEquals(5, view.remainingCount)
        assertTrue(view.low)
    }

    @Test
    fun `apply patch switches source to official`() {
        val quota = estimated(sent = 3, start = base)
        val patched = QuotaMath.applyPatch(
            quota,
            QuotaPatch(officialUsedFraction = 0.5f, officialResetAt = base + 2 * hour, officialDetail = "5 小时窗口"),
            base + 500
        )
        assertEquals(QuotaSource.OFFICIAL, patched.source)
        assertEquals(0.5f, patched.officialUsedFraction!!, 0.0001f)
        assertEquals(base + 2 * hour, patched.officialResetAt)
        assertEquals(3, patched.localSent) // 本地计数保留
        assertEquals("5 小时窗口", patched.officialDetail)
    }

    @Test
    fun `official fraction is clamped`() {
        val patched = QuotaMath.applyPatch(
            QuotaState(provider = ProviderId.GEMINI),
            QuotaPatch(officialUsedFraction = 4.2f),
            base
        )
        assertEquals(1f, patched.officialUsedFraction!!, 0.0001f)
    }

    @Test
    fun `settings limits per provider`() {
        val s = Settings(claudeLimit = 45, chatgptLimit = 80, geminiLimit = 100)
        assertEquals(45, s.limitFor(ProviderId.CLAUDE))
        assertEquals(80, s.limitFor(ProviderId.CHATGPT))
        assertEquals(100, s.limitFor(ProviderId.GEMINI))
        assertEquals(5 * hour, s.windowMillis())
    }

    @Test
    fun `register send clamps at hard ceiling`() {
        var quota = estimated(sent = 0, start = base, limit = 2)
        repeat(20) { quota = QuotaMath.registerSend(quota, base + it * 1000L, 5 * hour, 2) }
        assertTrue(quota.localSent <= 8)
    }
}
