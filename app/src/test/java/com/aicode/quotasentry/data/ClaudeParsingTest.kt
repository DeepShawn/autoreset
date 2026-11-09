package com.aicode.quotasentry.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Claude 额度 / 组织 JSON 的容错解析单测（fixture 覆盖多种真实形态）。
 */
class ClaudeParsingTest {

    private val now = Instant.parse("2026-11-09T12:00:00Z").toEpochMilli()

    @Test
    fun `网页版 usage 标准形态 utilization 为 0-100`() {
        val json = """
            {
              "five_hour": {"utilization": 67, "resets_at": "2026-11-09T15:00:00.000Z"},
              "seven_day": {"utilization": 24, "resets_at": "2026-11-12T00:00:00.000Z"},
              "limits": [
                {"kind": "weekly_scoped", "group": "weekly", "percent": 12,
                 "severity": "normal", "scope": {"model": {"display_name": "Opus"}}}
              ]
            }
        """.trimIndent()
        val patch = parseClaudeUsage(json, now)
        assertNotNull(patch)
        assertEquals(0.67f, patch!!.officialUsedFraction!!, 0.0001f)
        assertEquals(5L * 60 * 60 * 1000, patch.officialWindowMillis)
        assertEquals(Instant.parse("2026-11-09T15:00:00Z").toEpochMilli(), patch.officialResetAt)
        assertTrue(patch.officialDetail.contains("5 小时窗口已用 67%"))
        assertTrue(patch.officialDetail.contains("7 天窗口已用 24%"))
    }

    @Test
    fun `同一接口 utilization 为 0-1 的形态也能解析`() {
        val json = """
            {"five_hour": {"utilization": 0.42, "resets_at": "2026-11-09T15:00:00Z"}}
        """.trimIndent()
        val patch = parseClaudeUsage(json, now)!!
        assertEquals(0.42f, patch.officialUsedFraction!!, 0.0001f)
    }

    @Test
    fun `resets_at 支持 epoch 秒`() {
        val json = """{"five_hour": {"used_percent": 83, "reset_at": 1738300000}}"""
        val patch = parseClaudeUsage(json, now)!!
        assertEquals(0.83f, patch.officialUsedFraction!!, 0.0001f)
        assertEquals(1_738_300_000_000L, patch.officialResetAt)
    }

    @Test
    fun `resets_at 支持 epoch 毫秒`() {
        val json = """{"five_hour": {"utilization": 50, "resets_at": 1738300000123}}"""
        val patch = parseClaudeUsage(json, now)!!
        assertEquals(1_738_300_000_123L, patch.officialResetAt)
    }

    @Test
    fun `reset_after 相对秒数`() {
        val json = """{"five_hour": {"utilization": 50, "reset_after": 3600}}"""
        val patch = parseClaudeUsage(json, now)!!
        assertEquals(now + 3_600_000L, patch.officialResetAt)
    }

    @Test
    fun `嵌套 standard_rate_limit 形态`() {
        val json = """
            {
              "standard": {
                "rate_limit": {
                  "five_hour": {"utilization": 30, "resets_at": "2026-11-09T16:00:00Z"},
                  "seven_day": {"utilization": 10}
                }
              }
            }
        """.trimIndent()
        val patch = parseClaudeUsage(json, now)!!
        assertEquals(0.30f, patch.officialUsedFraction!!, 0.0001f)
    }

    @Test
    fun `limits 数组里的 five_hour 条目`() {
        val json = """
            {"limits": [{"kind": "five_hour", "percent": 55, "resets_at": "2026-11-09T16:00:00Z"}]}
        """.trimIndent()
        val patch = parseClaudeUsage(json, now)!!
        assertEquals(0.55f, patch.officialUsedFraction!!, 0.0001f)
    }

    @Test
    fun `只有七天窗口时退化为 7 天窗口`() {
        val json = """{"seven_day": {"utilization": 24, "resets_at": "2026-11-12T00:00:00Z"}}"""
        val patch = parseClaudeUsage(json, now)!!
        assertEquals(0.24f, patch.officialUsedFraction!!, 0.0001f)
        assertEquals(7L * 24 * 60 * 60 * 1000, patch.officialWindowMillis)
        assertTrue(patch.officialDetail.contains("7 天窗口"))
    }

    @Test
    fun `utilization 超范围会归一`() {
        val patch = parseClaudeUsage("""{"five_hour":{"utilization":120}}""", now)!!
        assertEquals(1f, patch.officialUsedFraction!!, 0.0001f)
    }

    @Test
    fun `非法或空 JSON 返回 null`() {
        assertNull(parseClaudeUsage("", now))
        assertNull(parseClaudeUsage("{}", now))
        assertNull(parseClaudeUsage("not json", now))
        assertNull(parseClaudeUsage("""{"five_hour":{"resets_at":"不是时间"}}""", now).let {
            // 只有无法解析的重置时间且没有用量 → null
            it
        })
    }

    @Test
    fun `组织列表解析与 plan 推断`() {
        val json = """
            [
              {"uuid": "org-123", "name": "Acme", "capabilities": ["chat", "claude_pro"],
               "billing_type": "stripe_subscription"},
              {"uuid": "org-456", "name": "Other"}
            ]
        """.trimIndent()
        val org = parseClaudeOrganizations(json)!!
        assertEquals("org-123", org.uuid)
        assertEquals("Pro", org.plan)
        assertEquals("Acme", org.label)
    }

    @Test
    fun `计划推断覆盖 max 与 enterprise`() {
        assertEquals("Max", inferClaudePlan(listOf("claude_max"), ""))
        assertEquals("Enterprise", inferClaudePlan(emptyList(), "enterprise_billing"))
        assertEquals("Team", inferClaudePlan(listOf("claude_team"), ""))
        assertEquals("", inferClaudePlan(emptyList(), "stripe_subscription"))
    }

    @Test
    fun `组织接口包了一层 organizations 也能解析`() {
        val json = """{"organizations": [{"uuid": "u-1", "capabilities": {"claude_max": true}}]}"""
        val org = parseClaudeOrganizations(json)!!
        assertEquals("u-1", org.uuid)
        assertEquals("Max", org.plan)
    }

    @Test
    fun `组织响应非法返回 null`() {
        assertNull(parseClaudeOrganizations("[]"))
        assertNull(parseClaudeOrganizations("not json"))
        assertNull(parseClaudeOrganizations("""[{"name":"没有 uuid"}]"""))
    }

    @Test
    fun `百分数展示`() {
        assertEquals("67%", percentText(0.67f))
        assertEquals("0%", percentText(0f))
        assertEquals("100%", percentText(1f))
    }
}
