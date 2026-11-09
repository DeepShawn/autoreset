package com.aicode.quotasentry.service

import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.ScheduleMode
import com.aicode.quotasentry.core.ScheduleRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [BackgroundMath] 的纯逻辑单测（不涉及 Android / 网络）。 */
class BackgroundMathTest {

    private fun rule(
        enabled: Boolean = true,
        lastRunAt: Long = 0L,
        mode: ScheduleMode = ScheduleMode.INTERVAL
    ) = ScheduleRule(
        id = "rule-test",
        provider = ProviderId.CLAUDE,
        enabled = enabled,
        mode = mode,
        lastRunAt = lastRunAt
    )

    @Test
    fun `没有计划时安排心跳唤醒`() {
        val now = 1_000_000L
        val target = BackgroundMath.nextWakeAt(now, emptyList())
        assertEquals(now + BackgroundMath.HEARTBEAT_MILLIS, target)
    }

    @Test
    fun `忽略无效时间并取最早的计划前 1 分钟`() {
        val now = 1_000_000L
        val target = BackgroundMath.nextWakeAt(
            now,
            listOf(0L, -5L, now + 10 * 60_000L, now + 3 * 60_000L)
        )
        assertEquals(now + 3 * 60_000L - BackgroundMath.ALARM_LEAD_MILLIS, target)
    }

    @Test
    fun `计划距现在太近时至少延迟 5 秒`() {
        val now = 1_000_000L
        val target = BackgroundMath.nextWakeAt(now, listOf(now + 10_000L))
        assertEquals(now + BackgroundMath.MIN_ALARM_DELAY_MILLIS, target)
    }

    @Test
    fun `计划已经过期时立即安排唤醒`() {
        val now = 1_000_000L
        val target = BackgroundMath.nextWakeAt(now, listOf(now - 10 * 60_000L))
        assertEquals(now + BackgroundMath.MIN_ALARM_DELAY_MILLIS, target)
    }

    @Test
    fun `连续失败每 3 次才提醒一次`() {
        assertFalse(BackgroundMath.shouldAlertFailure(0))
        assertFalse(BackgroundMath.shouldAlertFailure(1))
        assertFalse(BackgroundMath.shouldAlertFailure(2))
        assertTrue(BackgroundMath.shouldAlertFailure(3))
        assertFalse(BackgroundMath.shouldAlertFailure(4))
        assertFalse(BackgroundMath.shouldAlertFailure(5))
        assertTrue(BackgroundMath.shouldAlertFailure(6))
        assertTrue(BackgroundMath.shouldAlertFailure(9))
    }

    @Test
    fun `计划到点判断`() {
        val now = 1_000_000L
        // 正常到点
        assertTrue(BackgroundMath.isRuleDue(rule(), now, now - 1_000L))
        // 还没到
        assertFalse(BackgroundMath.isRuleDue(rule(), now, now + 1_000L))
        // 从未计算过 nextRunAt
        assertFalse(BackgroundMath.isRuleDue(rule(), now, 0L))
        // 已暂停
        assertFalse(BackgroundMath.isRuleDue(rule(enabled = false), now, now - 1_000L))
        // 刚执行过，30 秒内不重复
        assertFalse(BackgroundMath.isRuleDue(rule(lastRunAt = now - 5_000L), now, now - 1_000L))
        assertTrue(BackgroundMath.isRuleDue(rule(lastRunAt = now - 60_000L), now, now - 1_000L))
    }

    @Test
    fun `错过太久视为过期应重排`() {
        val now = 10L * 3600_000L
        val window = 3600_000L
        assertTrue(BackgroundMath.isStaleMiss(now, now - 2 * 3600_000L, window))
        assertFalse(BackgroundMath.isStaleMiss(now, now - 60_000L, window))
        assertFalse(BackgroundMath.isStaleMiss(now, now + 60_000L, window))
    }

    @Test
    fun `计算下一条计划倒计时`() {
        val now = 1_000_000L
        assertEquals(-1L, BackgroundMath.millisToNextSend(now, emptyList()))
        assertEquals(-1L, BackgroundMath.millisToNextSend(now, listOf(now - 5L)))
        assertEquals(120_000L, BackgroundMath.millisToNextSend(now, listOf(now + 120_000L, now - 5L)))
    }
}
