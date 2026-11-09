package com.aicode.quotasentry.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateParserTest {

    private fun releaseJson(
        tag: String = "v1.1.0",
        assets: String = """
            [
              {"name":"QuotaSentry-1.1.0.apk","size":1299836,"browser_download_url":"https://github.com/DeepShawn/autoreset/releases/download/v1.1.0/QuotaSentry-1.1.0.apk"},
              {"name":"使用手册.md","size":8326,"browser_download_url":"https://example.com/guide.md"}
            ]
        """.trimIndent()
    ): String = """
        {
          "tag_name":"$tag",
          "name":"额度哨兵 1.1.0",
          "body":"## 更新内容\n- 自动更新\n- Claude OAuth 额度",
          "html_url":"https://github.com/DeepShawn/autoreset/releases/tag/$tag",
          "published_at":"2026-11-10T00:00:00Z",
          "assets":$assets
        }
    """.trimIndent()

    @Test
    fun `parse release extracts apk asset`() {
        val info = UpdateParser.parseRelease(releaseJson())
        requireNotNull(info)
        assertEquals("1.1.0", info.versionName)
        assertEquals(1299836L, info.sizeBytes)
        assertTrue(info.downloadUrl.endsWith("QuotaSentry-1.1.0.apk"))
        assertTrue(info.notes.contains("自动更新"))
        assertEquals("https://github.com/DeepShawn/autoreset/releases/tag/v1.1.0", info.htmlUrl)
    }

    @Test
    fun `parse release prefers arm64 apk when both exist`() {
        val json = releaseJson(
            assets = """
                [
                  {"name":"QuotaSentry-1.1.0-universal.apk","size":100,"browser_download_url":"https://x/universal.apk"},
                  {"name":"QuotaSentry-1.1.0-arm64-v8a.apk","size":200,"browser_download_url":"https://x/arm64.apk"}
                ]
            """.trimIndent()
        )
        assertEquals("https://x/arm64.apk", UpdateParser.parseRelease(json)?.downloadUrl)
    }

    @Test
    fun `parse release without apk returns null`() {
        assertNull(UpdateParser.parseRelease(releaseJson(assets = """[{"name":"readme.md","size":1,"browser_download_url":"https://x/a.md"}]""")))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(UpdateParser.parseRelease("not a json"))
        assertNull(UpdateParser.parseRelease("{}"))
    }

    @Test
    fun `version comparison`() {
        assertTrue(UpdateParser.isNewer("1.1.0", "1.0.0"))
        assertTrue(UpdateParser.isNewer("v1.1.0", "1.0.9"))
        assertTrue(UpdateParser.isNewer("1.0.10", "1.0.9"))
        assertTrue(UpdateParser.isNewer("2.0", "1.9.9"))
        assertFalse(UpdateParser.isNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateParser.isNewer("v1.0", "1.0.0"))
        assertFalse(UpdateParser.isNewer("1.0.0", "1.1.0"))
    }

    @Test
    fun `normalize version strips v prefix`() {
        assertEquals("1.2.3", UpdateParser.normalizeVersion(" v1.2.3 "))
        assertEquals("1.2.3", UpdateParser.normalizeVersion("V1.2.3"))
        assertEquals(listOf(1, 2, 3), UpdateParser.versionParts("v1.2.3"))
    }

    @Test
    fun `human size`() {
        assertEquals("未知大小", UpdateParser.humanSize(-1))
        assertEquals("1 KB", UpdateParser.humanSize(1200))
        assertEquals("1.3 MB", UpdateParser.humanSize(1_350_000))
    }
}
