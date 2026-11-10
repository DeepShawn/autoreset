package com.aicode.quotasentry.auth

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthFlowTest {

    @Test
    fun `claude authorize url carries pkce and hosted redirect`() {
        val url = OAuthFlow.buildClaudeAuthorizeUrl("CHALLENGE", "STATE123")
        assertTrue(url.startsWith("https://claude.ai/oauth/authorize?"))
        assertTrue(url.contains("client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e"))
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback"))
        assertTrue(url.contains("code_challenge=CHALLENGE"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=STATE123"))
        assertTrue(url.contains("code=true"))
    }

    @Test
    fun `openai authorize url uses localhost loopback`() {
        val url = OAuthFlow.buildOpenAiAuthorizeUrl(
            "CHALLENGE", "STATE123", "http://127.0.0.1:1455/auth/callback"
        )
        assertTrue(url.startsWith("https://auth.openai.com/oauth/authorize?"))
        assertTrue(url.contains("client_id=app_EMoamEEZ73f0CkXaXp7hrann"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("scope=openid%20profile%20email%20offline_access%20api.connectors.read%20api.connectors.invoke"))
        assertTrue(url.contains("originator=codex_cli_rs"))
        assertTrue(url.contains("codex_cli_simplified_flow=true"))
    }

    @Test
    fun `parse claude code accepts code and code-hash-state`() {
        assertNotNull(OAuthFlow.parseClaudeCode("abc123def456ghi789#state-xyz"))
        assertNotNull(OAuthFlow.parseClaudeCode("  abc123def456ghi789  "))
    }

    @Test
    fun `parse claude code rejects junk and short text`() {
        assertNull(OAuthFlow.parseClaudeCode(null))
        assertNull(OAuthFlow.parseClaudeCode(""))
        assertNull(OAuthFlow.parseClaudeCode("short"))
        assertNull(OAuthFlow.parseClaudeCode("这是一段中文，不是授权码，长度足够长但格式不对"))
    }
}
