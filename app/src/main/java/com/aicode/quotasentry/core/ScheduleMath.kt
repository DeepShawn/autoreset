package com.aicode.quotasentry.core

import java.util.Calendar
import java.util.Locale

/** 定时计划的纯计算逻辑（可用单元测试覆盖）。 */
object ScheduleMath {

    private const val MINUTE = 60_000L

    /**
     * 计算某条规则下一次应当触发的时刻。
     *
     * @param quotaResetAt 官方窗口重置时刻（可为空）
     */
    fun computeNext(rule: ScheduleRule, now: Long, quotaResetAt: Long?, windowMillis: Long): Long {
        if (!rule.enabled) return 0L
        return when (rule.mode) {
            ScheduleMode.INTERVAL -> {
                val interval = rule.intervalMinutes.coerceIn(1, 24 * 60) * MINUTE
                val base = rule.lastRunAt.takeIf { it > 0L } ?: now
                maxOf(base + interval, now + 30_000L)
            }

            ScheduleMode.DAILY -> {
                val next = nextDaily(now, rule.atHour.coerceIn(0, 23), rule.atMinute.coerceIn(0, 59))
                if (next <= now) nextDaily(now + 24 * 3600_000L, rule.atHour, rule.atMinute) else next
            }

            ScheduleMode.FOLLOW_RESET -> {
                val window = windowMillis.coerceIn(10 * MINUTE, 24 * 3600_000L)
                val anchor = quotaResetAt?.takeIf { it > now - window / 2 }
                    ?: (rule.lastRunAt.takeIf { it > 0L }?.plus(window) ?: (now + window))
                // 官方重置往往提前几秒完成，这里提前 20 秒更稳
                val target = if (quotaResetAt != null) anchor - 20_000L else anchor
                if (target <= now) now + MINUTE else target
            }
        }
    }

    private fun nextDaily(now: Long, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var target = cal.timeInMillis
        if (target <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            target = cal.timeInMillis
        }
        return target
    }

    /** 当前是否有规则到了触发时间。 */
    fun isDue(rule: ScheduleRule, now: Long, nextAt: Long): Boolean = rule.enabled && nextAt in 1..now

    /** 规则的人类可读描述。 */
    fun describe(rule: ScheduleRule): String = when (rule.mode) {
        ScheduleMode.INTERVAL -> "每 ${rule.intervalMinutes} 分钟"
        ScheduleMode.DAILY -> String.format(Locale.US, "每天 %02d:%02d", rule.atHour, rule.atMinute)
        ScheduleMode.FOLLOW_RESET -> "跟随窗口重置"
    }

    /** 给设置页展示的“下一次”完整文案。 */
    fun describeNext(rule: ScheduleRule, nextAt: Long, now: Long): String {
        if (!rule.enabled) return "已暂停"
        if (nextAt <= 0L) return "等待生效"
        return "下次 " + Formatters.clock(nextAt) + "（" + Formatters.humanDuration(nextAt - now) + "后）"
    }
}
