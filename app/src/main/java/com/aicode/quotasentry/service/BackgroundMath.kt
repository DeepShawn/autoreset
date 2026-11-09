package com.aicode.quotasentry.service

import com.aicode.quotasentry.core.ScheduleRule

/**
 * 后台调度中不依赖 Android Framework 的纯计算，抽出来方便单元测试。
 *
 * 注意：这里不做任何 IO / 网络 / Store 读写，只做时间与阈值的判断。
 */
object BackgroundMath {

    /** 计划触发前多久先唤醒服务（留给网络与系统调度抖动）。 */
    const val ALARM_LEAD_MILLIS: Long = 60_000L

    /** AlarmManager 最短延迟，避免传入“过去时刻”导致系统立即触发。 */
    const val MIN_ALARM_DELAY_MILLIS: Long = 5_000L

    /** 连续失败多少次才提醒一次（不轰炸用户）。 */
    const val FAILURE_ALERT_THRESHOLD: Int = 3

    /** 没有任何计划的保活心跳间隔。 */
    const val HEARTBEAT_MILLIS: Long = 30L * 60_000L

    /** 同一条计划两次执行的最小间隔，防止手动触发与 tick 撞车。 */
    const val MIN_RUN_GAP_MILLIS: Long = 30_000L

    /** 计划晚于此刻多久视为“已错过”（例如关机一整天），直接重排而不是补发。 */
    const val STALE_MISS_MILLIS: Long = 30L * 60_000L

    /**
     * 计算下一次兜底唤醒时刻。
     *
     * @param candidates 各条计划的下次触发时刻（epoch ms），只统计 > 0 的有效值
     * @param leadMillis 提前量
     * @return 0 表示无需安排；否则是触发时刻（epoch ms）
     */
    fun nextWakeAt(
        now: Long,
        candidates: List<Long>,
        leadMillis: Long = ALARM_LEAD_MILLIS
    ): Long {
        val valid = candidates.filter { it > 0L }
        if (valid.isEmpty()) return now + HEARTBEAT_MILLIS
        val earliest = valid.min()
        val target = earliest - leadMillis.coerceAtLeast(0L)
        return if (target <= now + MIN_ALARM_DELAY_MILLIS) {
            now + MIN_ALARM_DELAY_MILLIS
        } else {
            target
        }
    }

    /**
     * 连续失败是否应当提醒：第 3、6、9… 次提醒，其余静默。
     * 发送成功时调用方应把计数清零。
     */
    fun shouldAlertFailure(
        consecutiveFailures: Int,
        threshold: Int = FAILURE_ALERT_THRESHOLD
    ): Boolean {
        if (consecutiveFailures <= 0) return false
        if (threshold <= 0) return true
        return consecutiveFailures % threshold == 0
    }

    /**
     * 计划是否到点可执行。
     *
     * @param nextAt 计划的下次触发时刻（Store 里持久化的 nextRunAt）
     */
    fun isRuleDue(
        rule: ScheduleRule,
        now: Long,
        nextAt: Long,
        minGapMillis: Long = MIN_RUN_GAP_MILLIS
    ): Boolean {
        if (!rule.enabled) return false
        if (nextAt <= 0L || nextAt > now) return false
        if (rule.lastRunAt > 0L && now - rule.lastRunAt < minGapMillis) return false
        return true
    }

    /** 计划是不是“晚点太多”，应当重排而不是补发。 */
    fun isStaleMiss(now: Long, nextAt: Long, windowMillis: Long): Boolean {
        return nextAt > 0L && now - nextAt > windowMillis.coerceAtLeast(STALE_MISS_MILLIS)
    }

    /**
     * 距离下一条计划还有多久（毫秒）。没有未来计划时返回 -1。
     */
    fun millisToNextSend(now: Long, nextRunAts: List<Long>): Long {
        val next = nextRunAts.filter { it > now }.minOrNull() ?: return -1L
        return next - now
    }
}
