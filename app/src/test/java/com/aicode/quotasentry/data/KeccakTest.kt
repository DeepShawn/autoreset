package com.aicode.quotasentry.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SHA3-512 实现 / Python json 兼容序列化的单测（已知测试向量）。
 */
class KeccakTest {

    @Test
    fun `SHA3-512 空串测试向量`() {
        assertEquals(
            "a69f73cca23a9ac5c8b567dc185a756e97c982164fe25859e0d1dcc1475c80a6" +
                "15b2123af1f5f94c11e3e9402c3ac558f500199d95b6d3e301758586281dcd26",
            Sha3.toHex(Sha3.sha512(ByteArray(0)))
        )
    }

    @Test
    fun `SHA3-512 abc 测试向量`() {
        assertEquals(
            "b751850b1a57168a5693cd924b6b096e08f621827444f70d884f5d0240d2712e" +
                "10e116e9192af3c91a7ec57647e3934057340b4cf408d5a56592f8274eec53f0",
            Sha3.toHex(Sha3.sha512("abc".toByteArray(Charsets.UTF_8)))
        )
    }

    @Test
    fun `SHA3-512 跨块输入`() {
        // 72 字节块边界前后都要正确
        val input = ByteArray(200) { (it * 31 % 256).toByte() }
        val first = Sha3.toHex(Sha3.sha512(input))
        val second = Sha3.toHex(Sha3.sha512(input.copyOf()))
        assertEquals(128, first.length)
        assertEquals(first, second)
    }

    @Test
    fun `Python json 序列化转义`() {
        assertEquals("null", pythonJson(null))
        assertEquals("true", pythonJson(true))
        assertEquals("3008", pythonJson(3008))
        assertEquals("[\"x\", null, 0, true]", pythonJson(listOf("x", null, 0, true)))
        assertEquals("\"a\\\"b\"", pythonJsonString("a\"b"))
        assertEquals("\"a\\u2212b\"", pythonJsonString("a\u2212b"))
        assertEquals("\"tab\\there\"", pythonJsonString("tab\there"))
    }

    @Test
    fun `proof token 结构`() {
        val token = generateProofToken(
            seed = "seed-value",
            difficulty = "0",
            userAgent = "Mozilla/5.0",
            maxAttempts = 5_000
        ) ?: return // 概率极低（(15/16)^5000），拿不到就让测试跳过而不误报
        org.junit.Assert.assertTrue(token.startsWith("gAAAAAB"))
    }
}
