package com.aicode.quotasentry.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * Gemini 页面 token 正则、StreamGenerate 请求体构造、响应判定的单测。
 */
class GeminiParsingTest {

    private val html = """
        <!DOCTYPE html><html><head><script>
        window.WIZ_global_data = {
          "SNlM0e":"AKfycby-abc123",
          "cfb2h":"boq_assistant-bard-web-server_20260101.01_p0",
          "FdrFJe":"-1234567890123456789",
          "isAdvanced":true
        };
        </script></head><body>Gemini</body></html>
    """.trimIndent()

    @Test
    fun `页面 token 提取`() {
        val tokens = parseGeminiTokens(html)!!
        assertEquals("AKfycby-abc123", tokens.at)
        assertEquals("boq_assistant-bard-web-server_20260101.01_p0", tokens.bl)
        assertEquals("-1234567890123456789", tokens.sid)
        assertEquals("Gemini Advanced", tokens.planHint)
    }

    @Test
    fun `token 提取容忍空格与 JSON 转义`() {
        val page = """
            <script>{"SNlM0e" : "abc\"def", "cfb2h":"bl-1"}</script>
        """.trimIndent()
        val tokens = parseGeminiTokens(page)!!
        assertEquals("abc\"def", tokens.at)
        assertEquals("bl-1", tokens.bl)
        assertEquals("", tokens.sid)
        assertEquals("", tokens.planHint)
    }

    @Test
    fun `缺少 SNlM0e 返回 null`() {
        assertNull(parseGeminiTokens("<html>login</html>"))
        assertNull(parseGeminiTokens(""))
    }

    @Test
    fun `inner 请求数组结构`() {
        val inner = buildGeminiInnerRequest("Hi")
        assertEquals(81, inner.length())
        val message = inner.optJSONArray(0)!!
        assertEquals("Hi", message.optString(0))
        assertEquals(0, message.optInt(1))
        assertEquals(JSONObject.NULL, message.opt(2))
        assertEquals(0, message.optInt(6))
        assertEquals(1, inner.optInt(7)) // 流式标记
        assertEquals("zh-CN", inner.optJSONArray(1)!!.optString(0))
        assertTrue(inner.optString(59).length >= 32)
    }

    @Test
    fun `当前网页版表单结构 at 加 f_req`() {
        val fields = buildGeminiFormFields("AKfycby-abc", "Hi", 12345L)
        assertEquals("AKfycby-abc", fields["at"])
        val fReq = fields["f.req"]!!
        val outer = JSONArray(fReq)
        assertEquals(2, outer.length())
        assertEquals(JSONObject.NULL, outer.opt(0))
        val innerJson = outer.optString(1)
        val inner = JSONArray(innerJson)
        assertEquals(81, inner.length())
        assertEquals("Hi", inner.optJSONArray(0)!!.optString(0))
    }

    @Test
    fun `老 Bard 表单结构`() {
        val fields = buildGeminiLegacyFormFields("at-token", "Hi", 1L)
        assertEquals("at-token", fields["at"])
        val outer = JSONArray(fields["f.req"]!!)
        assertEquals(1, outer.length())
        val pair = outer.optJSONArray(0)!!
        assertEquals(2, pair.length())
        assertEquals(JSONObject.NULL, pair.opt(0))
        val innerJson = pair.optString(1)
        val inner = JSONArray(innerJson)
        assertEquals(2, inner.length())
        assertEquals(JSONObject.NULL, inner.opt(0))
        assertEquals("Hi", inner.optJSONArray(1)!!.optString(0))
    }

    @Test
    fun `StreamGenerate URL 拼装`() {
        val tokens = GeminiTokens(at = "at-1", bl = "boq web server", sid = "-123", planHint = "")
        val url = buildGeminiUrl(tokens, 98765L)
        assertTrue(url.startsWith(STREAM_GENERATE_URL + "?"))
        assertTrue(url.contains("bl=boq+web+server"))
        assertTrue(url.contains("f.sid=-123"))
        assertTrue(url.contains("hl=zh-CN"))
        assertTrue(url.contains("_reqid=98765"))
        assertTrue(url.contains("rt=c"))
    }

    @Test
    fun `URL 参数可被解码`() {
        val tokens = GeminiTokens(at = "at-1", bl = "a&b=c", sid = "+1", planHint = "")
        val url = buildGeminiUrl(tokens, 1L)
        val query = url.substringAfter('?')
        val decoded = query.split('&').associate {
            val key = it.substringBefore('=')
            key to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        assertEquals("a&b=c", decoded["bl"])
        assertEquals("+1", decoded["f.sid"])
    }

    @Test
    fun `成功判定`() {
        assertTrue(
            geminiLooksSuccessful(
                200,
                ")]}'\n[[\"wrb.fr\",null,\"[[\\\"Hi!\\\"]]\"]]"
            )
        )
        assertFalse(geminiLooksSuccessful(200, ""))
        assertFalse(geminiLooksSuccessful(200, "<html>login</html>"))
        assertFalse(geminiLooksSuccessful(200, "[[\"wrb.fr\",null,[1037]]]"))
        assertFalse(geminiLooksSuccessful(429, "too many requests"))
    }

    @Test
    fun `已知错误码文案`() {
        assertTrue(geminiErrorText("[[1037]]")!!.contains("额度"))
        assertTrue(geminiErrorText("""{"error":1016}""")!!.contains("登录"))
        assertTrue(geminiErrorText("[[1014]]")!!.contains("暂时限制"))
        assertNull(geminiErrorText("[[1024]]"))
        assertTrue(geminiFailureText(401, "").contains("__Secure-1PSID"))
        assertTrue(geminiFailureText(200, "[[1037]]").contains("额度"))
    }

    @Test
    fun `quota 查询返回 Unsupported`() {
        val client = GeminiClient()
        assertEquals(com.aicode.quotasentry.core.ProviderId.GEMINI, client.id)
    }

    @Test
    fun `plan 提示只认 JSON 标记`() {
        assertEquals("", detectGeminiPlan("<div>Gemini Advanced 订阅</div>"))
        assertEquals("Gemini Advanced", detectGeminiPlan("""{"isAdvanced":true}"""))
        assertEquals("Google AI Plus", detectGeminiPlan("""{"isPlus":true}"""))
        assertNotNull(parseGeminiTokens(html))
    }
}
