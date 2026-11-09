package com.aicode.quotasentry.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

/**
 * ChatGPT session / wham usage / JWT claims / 请求体构造的单测。
 */
class ChatGPTParsingTest {

    private val now = Instant.parse("2026-11-09T12:00:00Z").toEpochMilli()

    private fun base64Url(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `wham usage 完整样例`() {
        val json = """
            {
              "plan_type": "plus",
              "rate_limit": {
                "primary_window": {
                  "used_percent": 6,
                  "reset_at": 1738300000,
                  "limit_window_seconds": 18000
                },
                "secondary_window": {
                  "used_percent": 24,
                  "reset_at": 1738900000,
                  "limit_window_seconds": 604800
                }
              },
              "credits": {"has_credits": true, "unlimited": false, "balance": 820.6969075},
              "rate_limit_reset_credits": {"available_count": 1}
            }
        """.trimIndent()
        val patch = parseChatGptUsage(json, now)
        assertNotNull(patch)
        assertEquals(0.06f, patch!!.officialUsedFraction!!, 0.0001f)
        assertEquals(18_000_000L, patch.officialWindowMillis)
        assertEquals(1_738_300_000_000L, patch.officialResetAt)
        assertEquals("Plus", patch.plan)
        assertTrue(patch.officialDetail.contains("5 小时窗口已用 6%"))
        assertTrue(patch.officialDetail.contains("7 天窗口已用 24%"))
    }

    @Test
    fun `conversation_limits 形态 used_limit`() {
        val json = """
            {"limits": [{"name": "gpt-4o", "limit": 40, "used": 10, "reset_after": 3600}]}
        """.trimIndent()
        val patch = parseChatGptUsage(json, now)!!
        assertEquals(0.25f, patch.officialUsedFraction!!, 0.0001f)
        assertEquals(now + 3_600_000L, patch.officialResetAt)
    }

    @Test
    fun `rate_limits 形态 remaining_limit`() {
        val json = """
            {"rate_limits": [{"window": "5h", "remaining": 30, "limit": 40, "reset_at": 1738300000}]}
        """.trimIndent()
        val patch = parseChatGptUsage(json, now)!!
        assertEquals(0.25f, patch.officialUsedFraction!!, 0.0001f)
        assertEquals(1_738_300_000_000L, patch.officialResetAt)
    }

    @Test
    fun `通用 utilization 形态`() {
        val json = """{"usage": {"utilization": 0.35, "resets_at": "2026-11-09T15:00:00Z"}}"""
        val patch = parseChatGptUsage(json, now)!!
        assertEquals(0.35f, patch.officialUsedFraction!!, 0.0001f)
    }

    @Test
    fun `空或非法响应返回 null`() {
        assertNull(parseChatGptUsage("", now))
        assertNull(parseChatGptUsage("{}", now))
        assertNull(parseChatGptUsage("<html>cloudflare</html>", now))
    }

    @Test
    fun `session 解析`() {
        val json = """
            {
              "user": {"id": "u-1", "name": "Tester", "email": "tester@example.com", "plan": "plus"},
              "expires": "2026-12-01T00:00:00Z",
              "accessToken": "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxIn0.sig"
            }
        """.trimIndent()
        val session = parseChatGptSession(json)!!
        assertEquals("tester@example.com", session.email)
        assertEquals("Plus", session.plan)
        assertTrue(session.accessToken.startsWith("eyJ"))
    }

    @Test
    fun `session 缺少 accessToken 返回 null`() {
        assertNull(parseChatGptSession("""{"user":{"email":"a@b.c"}}"""))
        assertNull(parseChatGptSession("not json"))
    }

    @Test
    fun `JWT claims 解析并支持无 padding 的 base64url`() {
        val payload = """
            {"email":"jwt-user@example.com",
             "https://api.openai.com/auth":{"chatgpt_plan_type":"pro","chatgpt_account_id":"acct-42"}}
        """.trimIndent()
        val jwt = base64Url("""{"alg":"RS256","typ":"JWT"}""") + "." + base64Url(payload) + ".signature"
        val claims = parseChatGptClaims(jwt)!!
        assertEquals("Pro", claims.plan)
        assertEquals("acct-42", claims.accountId)
        assertEquals("jwt-user@example.com", claims.email)
        assertNull(parseChatGptClaims("不是 JWT"))
        assertNull(parseChatGptClaims(null))
    }

    @Test
    fun `发送请求体符合契约`() {
        val json = JSONObject(buildChatGptConversationBody("Hi"))
        assertEquals("next", json.optString("action"))
        assertEquals("client-created-root", json.optString("parent_message_id"))
        assertEquals("auto", json.optString("model"))
        assertEquals(-480, json.optInt("timezone_offset_min"))
        assertTrue(json.optBoolean("history_and_training_disabled"))
        assertEquals("primary_assistant", json.optJSONObject("conversation_mode")!!.optString("kind"))
        val message = json.optJSONArray("messages")!!.optJSONObject(0)!!
        assertEquals("user", message.optJSONObject("author")!!.optString("role"))
        assertEquals("text", message.optJSONObject("content")!!.optString("content_type"))
        assertEquals("Hi", message.optJSONObject("content")!!.optJSONArray("parts")!!.optString(0))
        assertTrue(message.optString("id").isNotBlank())
    }

    @Test
    fun `sentinel 要求解析`() {
        val json = """
            {"token": "req-token-1",
             "proofofwork": {"required": true, "seed": "seed-abc", "difficulty": "0000"},
             "turnstile": {"required": false}}
        """.trimIndent()
        val requirements = parseChatRequirements(json)!!
        assertEquals("req-token-1", requirements.token)
        val proofOfWork = requirements.proofOfWork!!
        assertEquals("seed-abc", proofOfWork.seed)
        assertEquals("0000", proofOfWork.difficulty)
        assertTrue(proofOfWork.required)
        assertTrue(!requirements.turnstileRequired)
        assertNull(parseChatRequirements("{}"))
    }

    @Test
    fun `proof token 复刻 Python json dumps`() {
        // U+2212 必须转义成 \u2212，否则哈希对不上
        assertEquals("\"a\\u2212b\"", pythonJsonString("a\u2212b"))
        assertEquals("[\"x\", null, 0, true]", pythonJson(listOf("x", null, 0, true)))
    }

    @Test
    fun `proof token 生成`() {
        val token = generateProofToken(seed = "seed", difficulty = "0", userAgent = "UA-Test")
        assertNotNull(token)
        assertTrue(token!!.startsWith("gAAAAAB"))
        val payload = String(
            Base64.getDecoder().decode(token.removePrefix("gAAAAAB")),
            Charsets.UTF_8
        )
        assertTrue(payload.startsWith("["))
        assertTrue(payload.contains("UA-Test"))
        assertNull(generateProofToken(seed = "seed", difficulty = "", userAgent = "UA"))
    }

    @Test
    fun `错误文案针对性`() {
        assertTrue(chatGptFailureText(401, "").contains("Cookie"))
        assertTrue(chatGptFailureText(429, "").contains("额度"))
        assertTrue(chatGptFailureText(403, """{"detail":"Unusual activity detected"}""").contains("人机验证"))
        assertTrue(chatGptFailureText(403, "<!DOCTYPE html><html><body>Just a moment</body></html>").contains("Cloudflare"))
    }
}
