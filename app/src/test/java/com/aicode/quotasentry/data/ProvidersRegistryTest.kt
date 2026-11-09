package com.aicode.quotasentry.data

import com.aicode.quotasentry.core.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Providers 注册表单测：三个适配器都已注册，且构造过程不发网络请求。
 */
class ProvidersRegistryTest {

    @Test
    fun `三家适配器全部注册`() {
        ProviderId.ordered.forEach { provider ->
            assertTrue("${provider.key} 未注册", Providers.has(provider))
            assertEquals(provider, Providers.of(provider).id)
        }
        assertEquals(
            listOf("claude", "chatgpt", "gemini"),
            Providers.registeredKeys()
        )
        assertEquals(3, Providers.all.size)
    }

    @Test
    fun `清缓存不抛异常`() {
        Providers.clearCaches()
        ProviderId.ordered.forEach { Providers.clearCaches(it) }
        assertTrue(Providers.has(ProviderId.CLAUDE))
    }

    @Test
    fun `进程内缓存键值`() {
        ProviderCache.put(ProviderId.CLAUDE, "orgId", "org-1")
        assertEquals("org-1", ProviderCache.get(ProviderId.CLAUDE, "orgId"))
        ProviderCache.put(ProviderId.CLAUDE, "blank", "  ")
        assertEquals(null, ProviderCache.get(ProviderId.CLAUDE, "blank"))
        ProviderCache.remove(ProviderId.CLAUDE, "orgId")
        assertEquals(null, ProviderCache.get(ProviderId.CLAUDE, "orgId"))
        ProviderCache.clearAll()
    }
}
