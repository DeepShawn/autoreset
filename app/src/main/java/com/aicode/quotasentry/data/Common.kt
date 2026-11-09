package com.aicode.quotasentry.data

import com.aicode.quotasentry.core.Account
import com.aicode.quotasentry.core.ProviderId
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 适配器内部的一次接口调用结果：成功带值，失败带中文原因与是否属于登录失效。 */
internal sealed interface Res<out T> {
    data class Ok<T>(val value: T) : Res<T>
    data class Err(
        val message: String,
        val unauthorized: Boolean = false,
        /** 接口不存在/未开放（例如 Gemini 没有官方额度），调用方应退回本地估算而不是报错 */
        val unsupported: Boolean = false
    ) : Res<Nothing>
}

/**
 * 进程内的适配器缓存：orgId / accessToken / Gemini 页面 token / Claude 会话 id 等。
 *
 * 契约允许「不必持久化」：所有值都可以从 Cookie 重新推导，缓存只是省一次网络请求。
 * 线程安全，进程重启后自动失效；`Store` 里保存的 `Credentials.extra` 会优先于本缓存被读取。
 */
internal object ProviderCache {

    private val store = ConcurrentHashMap<ProviderId, ConcurrentHashMap<String, String>>()

    fun get(provider: ProviderId, key: String): String? =
        store[provider]?.get(key)?.takeIf { it.isNotBlank() }

    fun put(provider: ProviderId, key: String, value: String?) {
        if (value.isNullOrBlank()) return
        store.getOrPut(provider) { ConcurrentHashMap() }[key] = value
    }

    fun putAll(provider: ProviderId, values: Map<String, String?>) {
        values.forEach { (key, value) -> put(provider, key, value) }
    }

    fun remove(provider: ProviderId, key: String) {
        store[provider]?.remove(key)
    }

    fun clear(provider: ProviderId) {
        store.remove(provider)
    }

    fun clearAll() {
        store.clear()
    }

    /** 先查 `Credentials.extra`（由调用方持久化），再查进程内缓存。 */
    fun resolve(account: Account, key: String): String? =
        account.credentials.extra[key]?.takeIf { it.isNotBlank() } ?: get(account.provider, key)
}

/** Cookie / access token 的清洗与识别。用户从浏览器复制出来的东西经常有换行、前缀或多余分号。 */
internal object CredentialUtil {

    fun normalizeCookie(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("Cookie:", ignoreCase = true)) {
            text = text.substringAfter(':')
        }
        return text
            .replace("\r", ";")
            .replace("\n", ";")
            .replace(Regex(";\\s*;+"), ";")
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("; ")
    }

    fun cookieValue(cookie: String, name: String): String? {
        for (segment in cookie.split(';')) {
            val trimmed = segment.trim()
            val index = trimmed.indexOf('=')
            if (index <= 0) continue
            if (trimmed.substring(0, index).trim() == name) {
                return trimmed.substring(index + 1).trim()
            }
        }
        return null
    }

    fun hasCookie(cookie: String, name: String): Boolean =
        !cookieValue(cookie, name).isNullOrBlank()

    /** Claude 必须带 sessionKey；用户只粘贴了 `sk-ant-sid01-...` 时自动补成 Cookie。 */
    fun claudeCookie(raw: String): String {
        val cookie = normalizeCookie(raw)
        if (cookie.isEmpty()) return ""
        if (hasCookie(cookie, "sessionKey")) return cookie
        if (!cookie.contains('=') || cookie.startsWith("sk-ant-")) {
            return "sessionKey=$cookie"
        }
        return cookie
    }

    /** 以 `eyJ` 开头、恰好两个点的三段式字符串按 JWT（即 access token）处理。 */
    fun looksLikeJwt(raw: String): Boolean {
        val text = raw.trim().removePrefix("Bearer ").trim()
        return text.length >= 20 && text.startsWith("eyJ") && text.count { it == '.' } == 2
    }

    /**
     * ChatGPT 的凭据可能是完整 Cookie，也可能用户只粘贴了 access token。
     * 返回 (cookie, accessToken)，两者都可能为空。
     */
    fun chatGptCredential(raw: String): Pair<String, String> {
        val text = raw.trim()
        if (text.isEmpty()) return "" to ""
        val withoutBearer = text.removePrefix("Bearer ").trim()
        if (looksLikeJwt(withoutBearer) && !text.contains('=')) {
            return "" to withoutBearer
        }
        return normalizeCookie(text) to ""
    }

    fun geminiCookie(raw: String): String {
        val cookie = normalizeCookie(raw)
        if (cookie.isEmpty()) return ""
        if (hasCookie(cookie, "__Secure-1PSID")) return cookie
        if (!cookie.contains('=')) return "__Secure-1PSID=$cookie"
        return cookie
    }
}

/** 三家网页版内部接口都依赖浏览器指纹头，这里集中维护。 */
internal object BrowserHeaders {

    private const val CLAUDE_CLIENT_SHA = "8a753cbf88e19be0f5f67efefb1b07840b6402e9"

    fun claude(cookie: String, deviceId: String?): Map<String, String> = buildMap {
        put("Cookie", cookie)
        put("Accept", "application/json")
        put("User-Agent", HttpEngine.DEFAULT_USER_AGENT)
        put("Accept-Language", HttpEngine.DEFAULT_ACCEPT_LANGUAGE)
        put("Origin", "https://claude.ai")
        put("Referer", "https://claude.ai/new")
        // 网页版 /api/* 调用会带这三枚客户端标识；缺失时部分账号会被 403。
        put("anthropic-client-platform", "web_claude_ai")
        put("anthropic-client-version", "1.0.0")
        put("anthropic-client-sha", CLAUDE_CLIENT_SHA)
        if (!deviceId.isNullOrBlank()) put("anthropic-device-id", deviceId)
    }

    fun chatGpt(cookie: String, accessToken: String?, accept: String = "application/json"): Map<String, String> = buildMap {
        if (cookie.isNotBlank()) put("Cookie", cookie)
        if (!accessToken.isNullOrBlank()) put("Authorization", "Bearer $accessToken")
        put("Accept", accept)
        put("User-Agent", HttpEngine.DEFAULT_USER_AGENT)
        put("Accept-Language", HttpEngine.DEFAULT_ACCEPT_LANGUAGE)
        put("Origin", "https://chatgpt.com")
        put("Referer", "https://chatgpt.com/")
        put("oai-language", "zh-CN")
    }

    fun gemini(cookie: String): Map<String, String> = buildMap {
        put("Cookie", cookie)
        put("Accept", "*/*")
        put("User-Agent", HttpEngine.DEFAULT_USER_AGENT)
        put("Accept-Language", HttpEngine.DEFAULT_ACCEPT_LANGUAGE)
        put("Origin", "https://gemini.google.com")
        put("Referer", "https://gemini.google.com/app")
        put("X-Same-Domain", "1")
    }
}

/** 统一的 HTTP 错误文案（中文、可操作）。 */
internal fun httpFailureText(label: String, code: Int, body: String): String {
    val html = looksLikeHtml(body)
    return when {
        code == 401 -> "$label Cookie 已失效或未登录（HTTP 401），请重新登录后粘贴最新 Cookie"
        code == 403 && (html || looksLikeCloudflareChallenge(body)) ->
            "$label 触发了 Cloudflare 人机验证（HTTP 403）：请在浏览器打开 $label 完成验证后，" +
                "重新复制包含 cf_clearance 的 Cookie 再试"
        code == 403 ->
            "$label 拒绝了请求（HTTP 403）：Cookie 可能已过期，或账号触发了风控，请重新登录后再试"
        code == 404 -> "$label 接口地址可能已变更（HTTP 404），等待适配更新"
        code == 418 || code == 429 -> "$label 请求过于频繁或额度已用尽（HTTP $code），稍后再试"
        code in 500..599 -> "$label 服务器暂时不可用（HTTP $code），稍后重试"
        code == 400 || code == 422 -> {
            val detail = body.trim().take(120)
            "$label 请求被拒绝（HTTP $code）：接口参数可能已变更" + if (detail.isNotEmpty()) "，$detail" else ""
        }
        else -> "$label 返回异常（HTTP $code）"
    }
}

internal fun looksLikeHtml(body: String): Boolean {
    val text = body.trimStart()
    return text.startsWith("<!DOCTYPE", ignoreCase = true) ||
        text.startsWith("<html", ignoreCase = true) ||
        text.startsWith("<") && text.contains("<head", ignoreCase = true)
}

internal fun looksLikeCloudflareChallenge(body: String): Boolean {
    val lower = body.lowercase()
    return lower.contains("just a moment") ||
        lower.contains("cf-chl") ||
        lower.contains("challenge-platform") ||
        lower.contains("attention required") ||
        lower.contains("checking your browser")
}

/** 生成 UUIDv4 字符串（会话 / 消息 id 用）。 */
internal fun uuidV4(): String = UUID.randomUUID().toString()

/** Claude 空会话的 canonical null parent uuid。 */
internal const val CLAUDE_NULL_PARENT_UUID = "00000000-0000-4000-8000-000000000000"

/** 取出 SSE 样本里所有 data: 负载（已去 `[DONE]`）。 */
internal fun sseDataPayloads(sample: String): List<String> = sample.lineSequence()
    .map { it.trim() }
    .filter { it.startsWith("data:") }
    .map { it.removePrefix("data:").trim() }
    .filter { it.isNotEmpty() && it != "[DONE]" }
    .toList()

/** 从 SSE 样本里尽力提取服务端错误文案（Claude / ChatGPT 的错误帧格式不同）。 */
internal fun sseErrorDetail(sample: String): String? {
    for (payload in sseDataPayloads(sample)) {
        val json = parseJsonObjectLenient(payload) ?: continue
        val error = json.opt("error")
        when (error) {
            is String -> if (error.isNotBlank()) return error
            is JSONObject -> {
                val message = error.optString("message").ifBlank { error.optString("type") }
                if (message.isNotBlank()) return message
            }
        }
        if (json.optString("type") == "error") {
            val message = json.optString("message").ifBlank { json.optString("detail") }
            if (message.isNotBlank()) return message
        }
        val detail = json.optString("detail")
        if (detail.isNotBlank()) return detail
    }
    return null
}
