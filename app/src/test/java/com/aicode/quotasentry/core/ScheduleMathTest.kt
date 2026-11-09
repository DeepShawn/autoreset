package com.aicode.quotasentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ScheduleMathTest {

    private val hour = TimeUnit.HOURS.toMillis(1)
    private val minute = TimeUnit.MINUTES.toMillis(1)
    private val now = 1_700_000_000_000L

    private fun rule(
        mode: ScheduleMode = ScheduleMode.FOLLOW_RESET,
        enabled: Boolean = true,
        lastRunAt: Long = 0L,
        intervalMinutes: Int = 270,
        atHour: Int = 9,
        atMinute: Int = 0
    ) = ScheduleRule(
        id = "r1",
        provider = ProviderId.CLAUDE,
        enabled = enabled,
        mode = mode,
        intervalMinutes = intervalMinutes,
        atHour = atHour,
        atMinute = atMinute,
        lastRunAt = lastRunAt
    )

    @Test
    fun `disabled rule never fires`() {
        assertEquals(0L, ScheduleMath.computeNext(rule(enabled = false), now, null, 5 * hour))
    }

    @Test
    fun `interval schedules from last run`() {
        val next = ScheduleMath.computeNext(
            rule(mode = ScheduleMode.INTERVAL, lastRunAt = now - hour, intervalMinutes = 120),
            now, null, 5 * hour
        )
        assertEquals(now + hour, next)
    }

    @Test
    fun `interval never schedules in the past`() {
        val next = ScheduleMath.computeNext(
            rule(mode = ScheduleMode.INTERVAL, lastRunAt = now - 10 * hour, intervalMinutes = 60),
            now, null, 5 * hour
        )
        assertTrue(next >= now + 30_000L)
    }

    @Test
    fun `follow reset fires shortly before official reset`() {
        val resetAt = now + 2 * hour
        val next = ScheduleMath.computeNext(rule(mode = ScheduleMode.FOLLOW_RESET), now, resetAt, 5 * hour)
        assertEquals(resetAt - 20_000L, next)
    }

    @Test
    fun `follow reset without official data falls back to window`() {
        val next = ScheduleMath.computeNext(rule(mode = ScheduleMode.FOLLOW_RESET), now, null, 5 * hour)
        assertEquals(now + 5 * hour, next)
    }

    @Test
    fun `follow reset in the past schedules a minute later`() {
        val next = ScheduleMath.computeNext(rule(mode = ScheduleMode.FOLLOW_RESET), now, now - hour, 5 * hour)
        assertEquals(now + minute, next)
    }

    @Test
    fun `daily picks next occurrence today`() {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nearMidnight = cal.timeInMillis
        val next = ScheduleMath.computeNext(rule(mode = ScheduleMode.DAILY, atHour = 23, atMinute = 59), nearMidnight, null, 5 * hour)
        // 23:59 当天已过 → 次日 23:59
        assertTrue(next > nearMidnight)
        assertEquals(23, Calendar.getInstance().apply { timeInMillis = next }.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, Calendar.getInstance().apply { timeInMillis = next }.get(Calendar.MINUTE))
    }

    @Test
    fun `is due only inside tolerance`() {
        val r = rule()
        assertTrue(ScheduleMath.isDue(r, now, now - 1))
        assertTrue(ScheduleMath.isDue(r, now, now))
        assertTrue(!ScheduleMath.isDue(r, now, now + 1))
        assertTrue(!ScheduleMath.isDue(r.copy(enabled = false), now, now))
    }

    @Test
    fun `describe uses friendly labels`() {
        assertEquals("每 270 分钟", ScheduleMath.describe(rule(mode = ScheduleMode.INTERVAL)))
        assertEquals("跟随窗口重置", ScheduleMath.describe(rule(mode = ScheduleMode.FOLLOW_RESET)))
        assertTrue(ScheduleMath.describe(rule(mode = ScheduleMode.DAILY, atHour = 7, atMinute = 5)).contains("07:05"))
        assertTrue(ScheduleMath.describeNext(rule(), now + hour, now).startsWith("下次"))
        assertEquals("已暂停", ScheduleMath.describeNext(rule(enabled = false), now + hour, now))
    }
}
