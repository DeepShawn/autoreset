package com.aicode.quotasentry.data

import com.aicode.quotasentry.core.Account
import com.aicode.quotasentry.core.ConnectResult
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.QuotaFetch
import com.aicode.quotasentry.core.SendOutcome
import com.aicode.quotasentry.core.Settings

/**
 * 单个大模型服务的接入适配器。
 *
 * 三个服务都没有给个人账号的官方开放 API，这里用的是各自网页版前端使用的内部接口，
 * 凭据来自用户自己浏览器/内置 WebView 的登录 Cookie。接口随时可能变，适配器必须
 * 做好「失败可解释」：抛出异常一律转成带中文原因的结果。
 *
 * 实现者注意：
 * - 所有网络操作必须在 Dispatchers.IO 上执行；
 * - 只读/写用户自己的账号，不采集任何第三方数据；
 * - 失败时不要抛异常给 UI，返回 Fail / unsupported。
 */
interface ProviderClient {

    val id: ProviderId

    /** 建立/验证连接：换 orgId、access token、页面 token 等，尽量返回计划名与官方额度。 */
    suspend fun connect(account: Account, settings: Settings): ConnectResult

    /** 拉取官方额度；服务没有官方接口时返回 Unsupported，由本地窗口估算兜底。 */
    suspend fun fetchQuota(account: Account, settings: Settings): QuotaFetch

    /** 发送一条保活消息。 */
    suspend fun send(account: Account, settings: Settings, message: String): SendOutcome
}

/** Provider 适配器注册表。 */
object Providers {

    @Volatile
    private var registry: Map<ProviderId, ProviderClient> = emptyMap()

    private val lock = Any()

    /** 懒注册：首次访问时才构造适配器（构造过程无网络、无 Android 依赖）。 */
    private fun registry(): Map<ProviderId, ProviderClient> {
        val current = registry
        if (current.isNotEmpty()) return current
        return synchronized(lock) {
            if (registry.isEmpty()) {
                registry = mapOf(
                    ProviderId.CLAUDE to ClaudeClient(),
                    ProviderId.CHATGPT to ChatGPTClient(),
                    ProviderId.GEMINI to GeminiClient()
                )
            }
            registry
        }
    }

    fun of(provider: ProviderId): ProviderClient =
        registry()[provider] ?: error("provider ${provider.key} 未注册")

    val all: List<ProviderClient>
        get() = ProviderId.ordered.mapNotNull { registry()[it] }

    fun has(provider: ProviderId): Boolean = registry().containsKey(provider)

    /** 断开会话 / 更换凭据后清空进程内缓存（orgId、access token、Gemini 页面 token 等）。 */
    fun clearCaches(provider: ProviderId? = null) {
        if (provider == null) {
            ProviderCache.clearAll()
        } else {
            ProviderCache.clear(provider)
        }
    }

    /** 调试/单测用：注册表里当前有哪些 Provider 的字符串 key。 */
    fun registeredKeys(): List<String> = ProviderId.ordered
        .filter { has(it) }
        .map { it.key }
}
