package com.aicode.quotasentry.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 时间 / 量纲 / 计划名等纯工具函数的单测（不联网）。
 */
class JsonTimeTest {

    @Test
    fun `ISO8601 Z 时间`() {
        assertEquals(
            Instant.parse("2026-11-09T15:00:00Z").toEpochMilli(),
            TimeUtil.parseMillis("2026-11-09T15:00:00Z")
        )
        assertEquals(
            Instant.parse("2026-11-09T15:00:00.123456Z").toEpochMilli(),
            TimeUtil.parseMillis("2026-11-09T15:00:00.123456Z")
        )
    }

    @Test
    fun `ISO8601 带偏移`() {
        // +08:00 的 23:00 == UTC 15:00
        assertEquals(
            Instant.parse("2026-11-09T15:00:00Z").toEpochMilli(),
            TimeUtil.parseMillis("2026-11-09T23:00:00+08:00")
        )
        // 无冒号偏移 +0000
        assertEquals(
            Instant.parse("2026-11-09T15:00:00Z").toEpochMilli(),
            TimeUtil.parseMillis("2026-11-09T15:00:00+0000")
        )
    }

    @Test
    fun `不带时区的 ISO 时间按 UTC`() {
        assertEquals(
            Instant.parse("2026-11-09T15:00:00Z").toEpochMilli(),
            TimeUtil.parseMillis("2026-11-09T15:00:00")
        )
        assertEquals(
            Instant.parse("2026-11-09T15:00:00Z").toEpochMilli(),
            TimeUtil.parseMillis("2026-11-09 15:00:00")
        )
    }

    @Test
    fun `RFC1123 时间`() {
        assertEquals(
            Instant.parse("2026-11-09T14:53:02Z").toEpochMilli(),
            TimeUtil.parseMillis("Mon, 09 Nov 2026 14:53:02 GMT")
        )
    }

    @Test
    fun `epoch 秒与毫秒自动区分`() {
        assertEquals(1_738_300_000_000L, TimeUtil.parseMillis("1738300000"))
        assertEquals(1_738_300_000_000L, TimeUtil.parseMillis(1_738_300_000L.toString()))
        assertEquals(1_738_300_000_123L, TimeUtil.parseMillis("1738300000123"))
    }

    @Test
    fun `非法时间返回 null`() {
        assertNull(TimeUtil.parseMillis(null))
        assertNull(TimeUtil.parseMillis(""))
        assertNull(TimeUtil.parseMillis("not-a-time"))
        assertNull(TimeUtil.parseMillis("0"))
        assertNull(TimeUtil.parseMillis("-100"))
        assertNull(TimeUtil.parseMillis("20261109"))
    }

    @Test
    fun `持续时间文本解析`() {
        assertEquals(18_000_000L, TimeUtil.parseDurationMillis("5h"))
        assertEquals(18_000_000L, TimeUtil.parseDurationMillis("5 hours"))
        assertEquals(18_000_000L, TimeUtil.parseDurationMillis("18000s"))
        assertEquals(604_800_000L, TimeUtil.parseDurationMillis("7d"))
        assertEquals(1_800_000L, TimeUtil.parseDurationMillis("30m"))
        assertEquals(18_000_000L, TimeUtil.parseDurationMillis("18000"))
        assertNull(TimeUtil.parseDurationMillis("abc"))
    }

    @Test
    fun `相对重置时间`() {
        val now = 1_000_000L
        assertEquals(now + 3_600_000L, TimeUtil.parseRelativeMillis(now, 3600))
        assertEquals(now + 3_600_000L, TimeUtil.parseRelativeMillis(now, "3600"))
        assertEquals(now + 3_600_000L, TimeUtil.parseRelativeMillis(now, "1h"))
        assertNull(TimeUtil.parseRelativeMillis(now, null))
    }

    @Test
    fun `量纲归一 百分数与比例都支持`() {
        assertEquals(0.67f, normalizeUsedFraction("utilization", 67.0), 0.0001f)
        assertEquals(0.42f, normalizeUsedFraction("utilization", 0.42), 0.0001f)
        assertEquals(1f, normalizeUsedFraction("utilization", 120.0), 0.0001f)
        assertEquals(0.5f, normalizeUsedFraction("used_fraction", 0.5), 0.0001f)
        assertEquals(0.5f, normalizeUsedFraction("used_ratio", 50.0), 0.0001f)
        // 约定：>1.5 才是百分数，1 按 100% 处理（理论上无法区分的极端值）
        assertEquals(1f, normalizeUsedFraction("utilization", 1.0), 0.0001f)
        assertEquals(0f, normalizeUsedFraction("utilization", 0.0), 0.0001f)
    }

    @Test
    fun `计划名归一`() {
        assertEquals("Plus", planLabel("plus"))
        assertEquals("Pro", planLabel("claude_pro"))
        assertEquals("Max", planLabel("claude_max_5x"))
        assertEquals("Team", planLabel("team"))
        assertEquals("Enterprise", planLabel("enterprise"))
        assertEquals("Free", planLabel("free"))
        assertEquals("", planLabel(null))
        assertEquals("", planLabel("  "))
    }

    @Test
    fun `宽松 JSON 解析容忍前缀与杂字符`() {
        val obj = parseJsonObjectLenient(")]}'\n  {\"a\":1}  ")
        assertNotNull(obj)
        assertEquals(1, obj!!.optInt("a"))
        assertNull(parseJsonObjectLenient("完全不是 JSON"))
        assertNotNull(parseJsonArrayLenient("[1,2,3]"))
    }
}
