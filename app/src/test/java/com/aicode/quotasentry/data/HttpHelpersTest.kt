package com.aicode.quotasentry.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Http / Cookie / SSE 相关纯函数单测（不联网）。
 */
class HttpHelpersTest {

    @Test
    fun `表单编码`() {
        val encoded = HttpEngine.encodeForm(mapOf("a" to "b c", "d" to "中文"))
        assertEquals("a=b+c&d=%E4%B8%AD%E6%96%87", encoded)
    }

    @Test
    fun `SSE data 行识别`() {
        assertTrue(HttpEngine.hasSseDataLine("data: {\"x\":1}"))
        assertTrue(HttpEngine.hasSseDataLine(" data:  hello "))
        assertFalse(HttpEngine.hasSseDataLine("data: [DONE]"))
        assertFalse(HttpEngine.hasSseDataLine("data:"))
        assertFalse(HttpEngine.hasSseDataLine("event: message_start"))
        assertFalse(HttpEngine.hasSseDataLine(""))
    }

    @Test
    fun `SSE 样本判断与负载提取`() {
        val sample = """
            event: message_start
            data: {"type":"message_start"}

            event: content_block_delta
            data: {"delta":{"text":"Hi"}}
            data: [DONE]
        """.trimIndent()
        assertTrue(HttpEngine.sseSampleHasData(sample))
        assertEquals(2, sseDataPayloads(sample).size)
        assertTrue(sseDataPayloads(sample).first().contains("message_start"))
        assertFalse(HttpEngine.sseSampleHasData("event: ping\n\n: keep-alive"))
    }

    @Test
    fun `SSE 错误帧提取`() {
        val claude = """
            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}
        """.trimIndent()
        assertEquals("Overloaded", sseErrorDetail(claude))

        val chatGpt = """
            data: {"message":null,"error":"请求过于频繁"}
        """.trimIndent()
        assertEquals("请求过于频繁", sseErrorDetail(chatGpt))

        assertNull(sseErrorDetail("data: {\"type\":\"message_start\"}"))
    }

    @Test
    fun `Cookie 清洗`() {
        assertEquals(
            "sessionKey=abc; cf_clearance=xyz",
            CredentialUtil.normalizeCookie("Cookie: sessionKey=abc\ncf_clearance=xyz;")
        )
        assertEquals(
            "sessionKey=abc; a=b",
            CredentialUtil.normalizeCookie("sessionKey=abc;;; a=b")
        )
        assertEquals("", CredentialUtil.normalizeCookie(""))
    }

    @Test
    fun `只粘贴 token 时自动补 Cookie 名`() {
        assertEquals("sessionKey=sk-ant-sid01-xyz", CredentialUtil.claudeCookie("sk-ant-sid01-xyz"))
        assertEquals(
            "sessionKey=abc; cf_clearance=1",
            CredentialUtil.claudeCookie("sessionKey=abc; cf_clearance=1")
        )
        assertEquals("__Secure-1PSID=g.a000", CredentialUtil.geminiCookie("g.a000"))
        assertTrue(CredentialUtil.hasCookie("__Secure-1PSID=a; __Secure-1PSIDTS=b", "__Secure-1PSID"))
        assertFalse(CredentialUtil.hasCookie("a=b", "__Secure-1PSID"))
    }

    @Test
    fun `ChatGPT 凭据区分 Cookie 与 access token`() {
        val (cookie, token) = CredentialUtil.chatGptCredential("eyJhbGciOi.eyJzdWIiOi.sig")
        assertEquals("", cookie)
        assertTrue(token.startsWith("eyJ"))
        assertTrue(CredentialUtil.looksLikeJwt("Bearer eyJhbGciOi.eyJzdWIiOi.sig"))

        val second = CredentialUtil.chatGptCredential(
            "__Secure-next-auth.session-token=abc; cf_clearance=x"
        )
        assertTrue(second.first.contains("__Secure-next-auth.session-token"))
        assertEquals("", second.second)
    }

    @Test
    fun `HTTP 错误文案`() {
        assertTrue(httpFailureText("Claude", 401, "").contains("Cookie"))
        assertTrue(httpFailureText("Claude", 403, "").contains("403"))
        assertTrue(
            httpFailureText("Claude", 403, "<!DOCTYPE html><html>Just a moment</html>")
                .contains("Cloudflare")
        )
        assertTrue(httpFailureText("ChatGPT", 429, "").contains("额度"))
        assertTrue(httpFailureText("Gemini", 502, "").contains("服务器"))
        assertTrue(httpFailureText("Claude", 404, "").contains("接口地址"))
        assertTrue(httpFailureText("Claude", 400, "bad request").contains("bad request"))
    }

    @Test
    fun `HTML 与 Cloudflare 页面识别`() {
        assertTrue(looksLikeHtml("<!DOCTYPE html><html></html>"))
        assertTrue(looksLikeHtml("  <html><head></head></html>"))
        assertFalse(looksLikeHtml("""{"a":1}"""))
        assertTrue(looksLikeCloudflareChallenge("Checking your browser before accessing"))
        assertFalse(looksLikeCloudflareChallenge("""{"ok":true}"""))
    }

    @Test
    fun `UUID 生成格式`() {
        val values = (1..20).map { uuidV4() }
        values.forEach { value ->
            assertTrue(
                value,
                Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
                    .matches(value)
            )
        }
        assertEquals(20, values.toSet().size)
    }

    @Test
    fun `网络异常翻译为中文`() {
        assertTrue(HttpEngine.networkErrorText(java.net.UnknownHostException("x")).contains("域名"))
        assertTrue(HttpEngine.networkErrorText(java.net.ConnectException("x")).contains("无法连接"))
        assertTrue(HttpEngine.networkErrorText(java.net.SocketTimeoutException("x")).contains("超时"))
        assertTrue(HttpEngine.networkErrorText(javax.net.ssl.SSLException("x")).contains("HTTPS"))
    }
}
