package com.aicode.quotasentry.data

import com.aicode.quotasentry.core.Account
import com.aicode.quotasentry.core.Credentials
import com.aicode.quotasentry.core.ProviderId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * OAuth token 工具 / Claude /v1/messages / ChatGPT Codex 协议的单测（纯函数，不联网）。
 */
class OAuthTokenTest {

    private fun b64(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    // ---------------------------------------------------------- refresh 响应解析

    @Test
    fun `解析 refresh 响应`() {
        val tokens = parseOAuthTokenResponse(
            """{"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"scope":"x"}"""
        )!!
        assertEquals("at-1", tokens.accessToken)
        assertEquals("rt-1", tokens.refreshToken)
        assertEquals(3600L, tokens.expiresInSeconds)
    }

    @Test
    fun `refresh 响应字段缺失容错`() {
        val partial = parseOAuthTokenResponse("""{"access_token":"at-2"}""")!!
        assertEquals("at-2", partial.accessToken)
        assertEquals("", partial.refreshToken)
        assertEquals(0L, partial.expiresInSeconds)

        // expires_in 以字符串返回
        val stringExpiry = parseOAuthTokenResponse(
            """{"access_token":"at","expires_in":"7200"}"""
        )!!
        assertEquals(7200L, stringExpiry.expiresInSeconds)

        assertNull(parseOAuthTokenResponse("""{"refresh_token":"rt"}"""))
        assertNull(parseOAuthTokenResponse("not json"))
        assertNull(parseOAuthTokenResponse(""))
    }

    // ---------------------------------------------------------- 凭据读写

    @Test
    fun `过期判断`() {
        val now = 1_000_000L
        val margin = 5 * 60_000L
        fun account(expiresAt: Long) = Account(
            provider = ProviderId.CLAUDE,
            credentials = Credentials(
                accessToken = "at",
                extra = mapOf(OAuthCredentialStore.KEY_EXPIRES_AT to expiresAt.toString())
            )
        )
        assertTrue(OAuthCredentialStore.isExpiring(account(now + 60_000L), now, margin))
        assertTrue(OAuthCredentialStore.isExpiring(account(now - 1L), now, margin))
        assertFalse(OAuthCredentialStore.isExpiring(account(now + 10 * 60_000L), now, margin))
        // 没有过期时间：交给接口 401 判断
        assertFalse(
            OAuthCredentialStore.isExpiring(
                Account(provider = ProviderId.CLAUDE, credentials = Credentials(accessToken = "at")),
                now,
                margin
            )
        )
    }

    @Test
    fun `刷新结果写回 account`() {
        val now = 1_000_000L
        val margin = 5 * 60_000L
        val account = Account(
            provider = ProviderId.CLAUDE,
            credentials = Credentials(
                accessToken = "old-access",
                extra = mapOf(
                    OAuthCredentialStore.KEY_REFRESH_TOKEN to "old-refresh",
                    OAuthCredentialStore.KEY_EXPIRES_AT to "1"
                )
            )
        )
        // 响应里没有新 refresh token → 保留旧值
        val updated = OAuthCredentialStore.applyTokens(
            account,
            OAuthTokenSet("new-access", "", 3600),
            now,
            margin,
            mapOf(OAuthCredentialStore.KEY_ACCOUNT_ID to "acct-1")
        )
        assertEquals("new-access", updated.credentials.accessToken)
        assertEquals(
            "old-refresh",
            updated.credentials.extra[OAuthCredentialStore.KEY_REFRESH_TOKEN]
        )
        assertEquals(
            (now + 3_600_000L - margin).toString(),
            updated.credentials.extra[OAuthCredentialStore.KEY_EXPIRES_AT]
        )
        assertEquals("acct-1", updated.credentials.extra[OAuthCredentialStore.KEY_ACCOUNT_ID])
        // 原对象不被修改
        assertEquals("old-access", account.credentials.accessToken)

        // 响应带新 refresh token → 覆盖
        val rotated = OAuthCredentialStore.applyTokens(
            account,
            OAuthTokenSet("a2", "rt-new", 60),
            now,
            margin
        )
        assertEquals(
            "rt-new",
            rotated.credentials.extra[OAuthCredentialStore.KEY_REFRESH_TOKEN]
        )
    }

    @Test
    fun `刷新写回 Store 未初始化时不崩`() {
        val account = Account(
            provider = ProviderId.CLAUDE,
            credentials = Credentials(accessToken = "at")
        )
        val updated = account.copy(credentials = Credentials(accessToken = "at-2"))
        val result = OAuthCredentialStore.persist(account, updated)
        assertEquals("at-2", result.credentials.accessToken)
    }

    // ---------------------------------------------------------- Claude /v1/messages

    @Test
    fun `v1 messages 请求体`() {
        val json = JSONObject(buildClaudeMessagesBody("Hi"))
        assertEquals("claude-haiku-5-5", json.optString("model"))
        assertEquals(32, json.optInt("max_tokens"))
        val system = json.optJSONArray("system")!!.optJSONObject(0)!!
        assertEquals("text", system.optString("type"))
        assertEquals(CLAUDE_CODE_SYSTEM_PROMPT, system.optString("text"))
        val message = json.optJSONArray("messages")!!.optJSONObject(0)!!
        assertEquals("user", message.optString("role"))
        val block = message.optJSONArray("content")!!.optJSONObject(0)!!
        assertEquals("text", block.optString("type"))
        assertEquals("Hi", block.optString("text"))
    }

    @Test
    fun `v1 messages 请求头带 Claude Code 身份`() {
        val headers = claudeCodeMessagesHeaders("tok-1")
        assertEquals("Bearer tok-1", headers["Authorization"])
        assertEquals(CLAUDE_ANTHROPIC_VERSION, headers["anthropic-version"])
        assertEquals("claude-code-20250219,oauth-2025-04-20", headers["anthropic-beta"])
        assertEquals("true", headers["anthropic-dangerous-direct-browser-access"])
        assertEquals("claude-cli/2.1.280", headers["user-agent"])
        assertEquals("cli", headers["x-app"])
        assertEquals("application/json", headers["accept"])
    }

    @Test
    fun `v1 messages 成功与错误判定`() {
        // 200 正常消息体 → 成功
        assertNull(
            claudeMessagesFailure(
                200,
                """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"Hi"}]}"""
            )
        )
        // 200 但返回 error 结构 → 失败
        assertEquals(
            "Overloaded",
            claudeMessagesFailure(
                200,
                """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
            )
        )
        // 401 → 带服务端错误信息，调用方据此刷新 token
        assertEquals(
            "invalid bearer token",
            claudeMessagesFailure(
                401,
                """{"type":"error","error":{"type":"authentication_error","message":"invalid bearer token"}}"""
            )
        )
        // 429 → 额度文案
        assertTrue(claudeMessagesFailure(429, "")!!.contains("额度"))
        // 500 无 body → 带 HTTP 状态
        assertTrue(claudeMessagesFailure(500, "")!!.contains("500"))
    }

    @Test
    fun `错误体提取`() {
        assertEquals(
            "boom",
            apiErrorDetail("""{"error":{"message":"boom"}}""")
        )
        assertEquals("detail-1", apiErrorDetail("""{"detail":"detail-1"}"""))
        assertEquals("plain", apiErrorDetail("""{"error":"plain"}"""))
        assertNull(apiErrorDetail(""))
        assertNull(apiErrorDetail("{}"))
    }

    // ---------------------------------------------------------- ChatGPT Codex

    @Test
    fun `codex responses 请求体`() {
        val json = JSONObject(buildCodexResponsesBody("gpt-6.1-sol", "Hi"))
        assertEquals("gpt-6.1-sol", json.optString("model"))
        assertFalse(json.optBoolean("store", true))
        assertTrue(json.optBoolean("stream"))
        assertEquals("You are a helpful assistant.", json.optString("instructions"))
        val input = json.optJSONArray("input")!!.optJSONObject(0)!!
        assertEquals("message", input.optString("type"))
        assertEquals("user", input.optString("role"))
        val content = input.optJSONArray("content")!!.optJSONObject(0)!!
        assertEquals("input_text", content.optString("type"))
        assertEquals("Hi", content.optString("text"))
        assertEquals("low", json.optJSONObject("text")!!.optString("verbosity"))
        assertEquals(
            "reasoning.encrypted_content",
            json.optJSONArray("include")!!.optString(0)
        )
        assertEquals("auto", json.optString("tool_choice"))
        assertTrue(json.optBoolean("parallel_tool_calls"))
    }

    @Test
    fun `codex responses 请求头`() {
        val headers = codexResponsesHeaders("tok", "acct-9")
        assertEquals("Bearer tok", headers["Authorization"])
        assertEquals("acct-9", headers["chatgpt-account-id"])
        assertEquals("responses=experimental", headers["OpenAI-Beta"])
        assertEquals("pi", headers["originator"])
        assertEquals("text/event-stream", headers["accept"])
        assertEquals("application/json", headers["content-type"])
    }

    @Test
    fun `codex SSE 首帧与错误帧`() {
        val success = """
            event: response.created
            data: {"type":"response.created","response":{"id":"r1"}}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"Hi"}
        """.trimIndent()
        assertTrue(HttpEngine.sseSampleHasData(success))
        assertNull(chatGptSseError(success))

        val failed = """
            data: {"type":"error","message":"boom","code":"server_error"}
        """.trimIndent()
        assertEquals("boom", chatGptSseError(failed))

        val responseFailed = """
            data: {"type":"response.failed","response":{"error":{"message":"model overloaded"}}}
        """.trimIndent()
        assertEquals("model overloaded", chatGptSseError(responseFailed))
        assertNull(chatGptSseError("data: [DONE]"))
    }

    @Test
    fun `模型被拒判断`() {
        assertTrue(
            isModelRejected(400, """{"detail":"The model gpt-6.1-sol does not exist"}""")
        )
        assertTrue(isModelRejected(400, """{"detail":"model not supported"}"""))
        assertTrue(isModelRejected(404, """{"error":{"message":"model not found"}}"""))
        assertFalse(isModelRejected(400, """{"detail":"rate limit exceeded"}"""))
        assertFalse(isModelRejected(401, """{"detail":"model not found"}"""))
        assertFalse(isModelRejected(200, "model not found"))
    }

    @Test
    fun `JWT 里取 ChatGPT accountId 与 plan`() {
        val payload = """
            {"email":"codex@example.com",
             "https://api.openai.com/auth":{"chatgpt_plan_type":"plus",
                                            "chatgpt_account_id":"acct-42"}}
        """.trimIndent()
        val jwt = b64("""{"alg":"RS256"}""") + "." + b64(payload) + ".sig"
        val claims = parseChatGptClaims(jwt)!!
        assertEquals("acct-42", claims.accountId)
        assertEquals("Plus", claims.plan)
        assertEquals("codex@example.com", claims.email)
    }
}
