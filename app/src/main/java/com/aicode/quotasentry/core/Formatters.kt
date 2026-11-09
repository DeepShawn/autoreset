package com.aicode.quotasentry.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时间/文案格式化工具。 */
object Formatters {

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
    private val dateOnlyFmt = SimpleDateFormat("M月d日", Locale.getDefault())

    fun clock(millis: Long): String = clockFmt.format(Date(millis))

    fun dateTime(millis: Long): String = dateFmt.format(Date(millis))

    fun dateOnly(millis: Long): String = dateOnlyFmt.format(Date(millis))

    /** 4 小时 12 分 / 37 秒 */
    fun humanDuration(millis: Long): String {
        val safe = millis.coerceAtLeast(0)
        val totalMinutes = safe / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val seconds = (safe % 60_000L) / 1000
        return when {
            hours > 0 -> if (minutes > 0) "$hours 小时 $minutes 分" else "$hours 小时"
            minutes > 0 -> "$minutes 分"
            else -> "$seconds 秒"
        }
    }

    /** 倒计时，优先显示小时，用于卡片上的“还有 4 小时 12 分重置”。 */
    fun countdown(millis: Long): String {
        if (millis <= 0) return "即将重置"
        return humanDuration(millis) + "后"
    }

    fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0L) return "从未"
        val delta = now - millis
        return when {
            delta < 60_000L -> "刚刚"
            delta < 3600_000L -> "${delta / 60_000L} 分钟前"
            delta < 24 * 3600_000L -> "${delta / 3600_000L} 小时前"
            delta < 48 * 3600_000L -> "昨天"
            else -> dateOnly(millis)
        }
    }

    fun percent(fraction: Float): String {
        val p = (fraction.coerceIn(0f, 1f) * 100).toInt()
        return "$p%"
    }

    fun shortNumber(value: Int?): String = value?.toString() ?: "—"
}
