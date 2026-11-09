package com.aicode.quotasentry.data

import com.aicode.quotasentry.core.Account
import com.aicode.quotasentry.core.ConnectResult
import com.aicode.quotasentry.core.DefaultMessages
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.QuotaFetch
import com.aicode.quotasentry.core.SendOutcome
import com.aicode.quotasentry.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.random.Random

/**
 * Gemini（gemini.google.com 网页版）适配器。
 *
 * 端点（2025-11 用 HanaokaYuzu/Gemini-API 等公开逆向实现核实）：
 * - 页面 token：`GET https://gemini.google.com/app`（Cookie）→ 正则抓
 *   `"SNlM0e"`（at / XSRF token）、`"cfb2h"`（bl / build label）、`"FdrFJe"`（f.sid）。
 * - 发送：`POST https://gemini.google.com/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate`
 *   query：`bl`、`f.sid`、`hl=zh-CN`、`_reqid=<递增数字>`、`rt=c`；
 *   form body：`at=<SNlM0e>&f.req=<[null,"<81 字段 inner JSONArray 的字符串>"]>`。
 *   备用形态（老 Bard 接口）：只发 `f.req=[[null,"[null,[\"Hi\",0,null,null,null,null,0]]"]]`。
 *   返回 200 且 body 非空、不含错误码即视为成功（响应可能带 `)]}'` 前缀）。
 * - 官方额度：**没有**公开/稳定的个人额度接口（网页「用量」窗口走 WebSocket + 内部 RPC
 *   `GetUsageInfo(jSf9Qc)`，未文档化且随时会变，故 v1 明确返回 [QuotaFetch.Unsupported]，
 *   由本地滚动窗口估算兜底）。
 *
 * 不确定性（注释保留）：
 * - StreamGenerate 的 inner 数组字段（尤其 index 7 流式标记、index 59 turn uuid、79/80 模型位）
 *   来自逆向实现，Google 调整后可能 400；已保留 legacy 请求形态兜底。
 * - 桌面 Chrome UA 是必须的，移动 UA/无 UA 可能被拒。
 */
class GeminiClient : ProviderClient {

    override val id: ProviderId = ProviderId.GEMINI

    override suspend fun connect(account: Account, settings: Settings): ConnectResult =
        withContext(Dispatchers.IO) {
            try {
                connectInternal(account, settings)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                ConnectResult.Fail(HttpEngine.networkErrorText(t))
            }
        }

    /**
     * Gemini 没有官方额度接口：返回 Unsupported，UI 直接走本地估算。
     * （逆向实现里存在 GetUsageInfo RPC，但未公开、易变，不做依赖。）
     */
    override suspend fun fetchQuota(account: Account, settings: Settings): QuotaFetch =
        QuotaFetch.Unsupported("Gemini 没有可用的官方额度接口，已使用本地窗口估算")

    override suspend fun send(
        account: Account,
        settings: Settings,
        message: String
    ): SendOutcome = withContext(Dispatchers.IO) {
        try {
            sendInternal(account, settings, message)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            SendOutcome(ok = false, detail = HttpEngine.networkErrorText(t))
        }
    }

    // ------------------------------------------------------------------ connect

    private fun connectInternal(account: Account, settings: Settings): ConnectResult {
        val cookie = cookieOf(account)
        if (cookie.isBlank()) {
            return ConnectResult.Fail(
                "未填写 Gemini Cookie：请在浏览器登录 gemini.google.com，复制包含 " +
                    "__Secure-1PSID 的整段 Cookie（建议同时带上 __Secure-1PSIDTS）"
            )
        }
        if (!CredentialUtil.hasCookie(cookie, "__Secure-1PSID")) {
            return ConnectResult.Fail(
                "Cookie 缺少 __Secure-1PSID，无法登录 Gemini：请从浏览器完整复制 Cookie"
            )
        }
        val engine = HttpEngine(settings.proxy)
        val tokens = when (val result = loadTokens(engine, account, cookie, forceRefresh = true)) {
            is Res.Err -> return ConnectResult.Fail(result.message)
            is Res.Ok -> result.value
        }
        ProviderCache.put(id, KEY_AT, tokens.at)
        val plan = tokens.planHint
        if (plan.isNotBlank()) ProviderCache.put(id, KEY_PLAN, plan)
        return ConnectResult.Ok(plan = plan, label = "", quota = null)
    }

    // ------------------------------------------------------------------ send

    private fun sendInternal(
        account: Account,
        settings: Settings,
        message: String
    ): SendOutcome {
        val cookie = cookieOf(account)
        if (cookie.isBlank()) {
            return SendOutcome(
                ok = false,
                detail = "未填写 Gemini Cookie，请先在设置里连接账号",
                unauthorized = true
            )
        }
        val engine = HttpEngine(settings.proxy)
        val tokens = when (val result = loadTokens(engine, account, cookie, forceRefresh = false)) {
            is Res.Err -> return SendOutcome(false, result.message, result.unauthorized)
            is Res.Ok -> result.value
        }
        val prompt = message.trim().ifEmpty { DefaultMessages.forProvider(ProviderId.GEMINI) }
        val reqId = nextRequestId(account)
        val url = buildGeminiUrl(tokens, reqId)
        val headers = BrowserHeaders.gemini(cookie)

        // 1) 当前网页版请求形态：at + f.req=[null, "<81 字段 inner>"]
        val primaryFields = buildGeminiFormFields(tokens.at, prompt, reqId)
        when (val primary = engine.postForm(url, headers, primaryFields, maxBytes = GEMINI_SEND_MAX_BYTES)) {
            is HttpResult.Fail -> return SendOutcome(false, primary.message)
            is HttpResult.Ok -> {
                val response = primary.response
                if (geminiLooksSuccessful(response.code, response.body)) {
                    ProviderCache.put(id, KEY_AT, tokens.at)
                    return SendOutcome(ok = true, detail = "Gemini 已收到消息")
                }
                if (response.code == 401 || response.code == 403) {
                    ProviderCache.remove(id, KEY_AT)
                    return SendOutcome(
                        false,
                        geminiFailureText(response.code, response.body),
                        unauthorized = response.code == 401
                    )
                }
                // 页面 token 可能过期：清缓存后重新抓一次，再试老形态
                ProviderCache.remove(id, KEY_AT)
                val freshTokens = when (val refreshed = loadTokens(engine, account, cookie, forceRefresh = true)) {
                    is Res.Ok -> refreshed.value
                    is Res.Err -> tokens
                }
                val legacyUrl = buildGeminiUrl(freshTokens, nextRequestId(account))
                val legacyFields = buildGeminiLegacyFormFields(freshTokens.at, prompt, reqId)
                when (val legacy = engine.postForm(legacyUrl, headers, legacyFields, maxBytes = GEMINI_SEND_MAX_BYTES)) {
                    is HttpResult.Fail -> return SendOutcome(false, legacy.message)
                    is HttpResult.Ok -> {
                        val legacyResponse = legacy.response
                        if (geminiLooksSuccessful(legacyResponse.code, legacyResponse.body)) {
                            return SendOutcome(ok = true, detail = "Gemini 已收到消息（备用请求形态）")
                        }
                        val detail = geminiFailureText(legacyResponse.code, legacyResponse.body)
                        return SendOutcome(
                            ok = false,
                            detail = detail,
                            unauthorized = legacyResponse.code == 401
                        )
                    }
                }
            }
        }
        return SendOutcome(ok = false, detail = "Gemini 发送失败：接口未返回可识别结果，请稍后重试")
    }

    // ------------------------------------------------------------------ 页面 token

    private fun loadTokens(
        engine: HttpEngine,
        account: Account,
        cookie: String,
        forceRefresh: Boolean
    ): Res<GeminiTokens> {
        if (!forceRefresh) {
            val cachedAt = ProviderCache.resolve(account, KEY_AT)
            val cachedBl = ProviderCache.resolve(account, KEY_BL)
            val cachedSid = ProviderCache.resolve(account, KEY_SID)
            if (!cachedAt.isNullOrBlank()) {
                return Res.Ok(
                    GeminiTokens(
                        at = cachedAt,
                        bl = cachedBl.orEmpty(),
                        sid = cachedSid.orEmpty(),
                        planHint = ProviderCache.resolve(account, KEY_PLAN).orEmpty()
                    )
                )
            }
        }
        val headers = BrowserHeaders.gemini(cookie)
        val response = when (val result = engine.get(URL_APP, headers, maxBytes = HttpEngine.MAX_PAGE_BYTES)) {
            is HttpResult.Fail -> return Res.Err(result.message)
            is HttpResult.Ok -> result.response
        }
        // 被重定向到 Google 登录/同意页说明 Cookie 失效
        if (response.finalUrl.contains("accounts.google.com", ignoreCase = true) ||
            response.finalUrl.contains("/signin", ignoreCase = true)
        ) {
            return Res.Err(
                "Gemini Cookie 已失效（被重定向到 Google 登录页），请重新登录并复制最新 Cookie",
                unauthorized = true
            )
        }
        if (!response.ok) {
            return Res.Err(
                geminiFailureText(response.code, response.body),
                unauthorized = response.code == 401 || response.code == 403
            )
        }
        val tokens = parseGeminiTokens(response.body)
            ?: return Res.Err(
                "无法从 Gemini 页面解析 SNlM0e token（可能未登录或接口已变更），" +
                    "请重新登录 gemini.google.com 后复制最新 Cookie",
                unauthorized = true
            )
        ProviderCache.putAll(
            id,
            mapOf(KEY_AT to tokens.at, KEY_BL to tokens.bl, KEY_SID to tokens.sid)
        )
        if (tokens.planHint.isNotBlank()) ProviderCache.put(id, KEY_PLAN, tokens.planHint)
        return Res.Ok(tokens)
    }

    private fun nextRequestId(account: Account): Long {
        val current = ProviderCache.resolve(account, KEY_REQ_ID)?.toLongOrNull()
        val next = if (current == null || current <= 0) {
            Random.nextLong(10_000, 99_999)
        } else {
            current + 100_000
        }
        ProviderCache.put(id, KEY_REQ_ID, next.toString())
        return next
    }

    private fun cookieOf(account: Account): String =
        CredentialUtil.geminiCookie(account.credentials.cookie)

    private companion object {
        const val URL_APP = "https://gemini.google.com/app"
        const val URL_STREAM_GENERATE =
            "https://gemini.google.com/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate"
        const val KEY_AT = "geminiAt"
        const val KEY_BL = "geminiBl"
        const val KEY_SID = "geminiSid"
        const val KEY_PLAN = "plan"
        const val KEY_REQ_ID = "geminiReqId"
        const val GEMINI_SEND_MAX_BYTES = 256 * 1024
    }
}

// ---------------------------------------------------------------------- 纯解析函数（可单测）

internal data class GeminiTokens(
    val at: String,
    val bl: String,
    val sid: String,
    val planHint: String = ""
)

/**
 * 从 Gemini 首页 HTML 中提取页面 token。
 *
 * 参考实现的正则：`"SNlM0e":\s*"(.*?)"`、`"cfb2h"`、`"FdrFJe"`。
 * 这里额外支持 JSON 转义字符，避免值里出现 `\"` 时截断。
 */
internal fun parseGeminiTokens(html: String): GeminiTokens? {
    val at = extractJsStringField(html, "SNlM0e")
        ?: extractJsStringField(html, "SNlM0e\"")
    if (at.isNullOrBlank()) return null
    val bl = extractJsStringField(html, "cfb2h").orEmpty()
    val sid = extractJsStringField(html, "FdrFJe").orEmpty()
    return GeminiTokens(at = at, bl = bl, sid = sid, planHint = detectGeminiPlan(html))
}

/** 抓取页面内某个 JSON 字段的字符串值，处理最小集合的 JSON 转义。 */
internal fun extractJsStringField(html: String, key: String): String? {
    val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    val match = pattern.find(html) ?: return null
    return unescapeJsonString(match.groupValues[1])
}

private fun unescapeJsonString(raw: String): String {
    if (!raw.contains('\\')) return raw
    val builder = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        if (char != '\\' || index == raw.length - 1) {
            builder.append(char)
            index++
            continue
        }
        val next = raw[index + 1]
        when (next) {
            '"' -> builder.append('"')
            '\\' -> builder.append('\\')
            '/' -> builder.append('/')
            'n' -> builder.append('\n')
            'r' -> builder.append('\r')
            't' -> builder.append('\t')
            'b' -> builder.append('\b')
            'f' -> builder.append('\u000C')
            'u' -> {
                if (index + 5 < raw.length) {
                    val hex = raw.substring(index + 2, index + 6)
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        builder.append(code.toChar())
                        index += 6
                        continue
                    }
                }
                builder.append(next)
            }
            else -> builder.append(next)
        }
        index += 2
    }
    return builder.toString()
}

/** 计划提示：只认页面里的高级能力 JSON 标记，避免被营销文案误判。 */
internal fun detectGeminiPlan(html: String): String = when {
    html.contains("\"isAdvanced\":true") || html.contains("\"advanced\":true") -> "Gemini Advanced"
    html.contains("\"isPlus\":true") || html.contains("\"plus\":true") -> "Google AI Plus"
    else -> ""
}

/** 当前网页版请求体：`at=<SNlM0e>&f.req=<[null,"<81 字段 inner>"]>`。 */
internal fun buildGeminiFormFields(at: String, prompt: String, reqId: Long): Map<String, String> {
    val inner = buildGeminiInnerRequest(prompt)
    val fReq = JSONArray()
        .put(JSONObject.NULL)
        .put(inner.toString())
        .toString()
    return mapOf(
        "at" to at,
        "f.req" to fReq
    )
}

/**
 * 老 Bard 请求体：`f.req=[[null,"[null,[\"Hi\",0,null,null,null,null,0]]"]]`。
 * 只作为新形态失败后的兜底。
 */
internal fun buildGeminiLegacyFormFields(at: String, prompt: String, reqId: Long): Map<String, String> {
    val messageContent = JSONArray()
        .put(prompt)
        .put(0)
        .put(JSONObject.NULL)
        .put(JSONObject.NULL)
        .put(JSONObject.NULL)
        .put(JSONObject.NULL)
        .put(0)
    val inner = JSONArray()
        .put(JSONObject.NULL)
        .put(messageContent)
        .toString()
    val fReq = JSONArray()
        .put(
            JSONArray()
                .put(JSONObject.NULL)
                .put(inner)
        )
        .toString()
    val fields = mutableMapOf("f.req" to fReq)
    if (at.isNotBlank()) fields["at"] = at
    return fields
}

/**
 * 当前逆向实现使用的 81 字段 inner 数组（长度必须为 81）。
 * 字段含义大多未知，按 Gemini-API 的实现照搬：index 0 是消息体、index 7 是流式标记、
 * index 59 是本次 turn 的 uuid、index 79/80 是模型位。
 */
internal fun buildGeminiInnerRequest(prompt: String, language: String = "zh-CN"): JSONArray {
    val inner = JSONArray()
    repeat(INNER_LENGTH) { inner.put(JSONObject.NULL) }

    val messageContent = JSONArray()
        .put(prompt)
        .put(0)
        .put(JSONObject.NULL)
        .put(JSONObject.NULL)
        .put(JSONObject.NULL)
        .put(JSONObject.NULL)
        .put(0)
    inner.put(0, messageContent)
    inner.put(1, JSONArray().put(language))
    inner.put(
        2,
        JSONArray()
            .put("").put("").put("")
            .put(JSONObject.NULL).put(JSONObject.NULL).put(JSONObject.NULL)
            .put(JSONObject.NULL).put(JSONObject.NULL).put(JSONObject.NULL)
            .put("")
    )
    inner.put(6, JSONArray().put(1))
    inner.put(7, 1) // 流式标记
    inner.put(10, 1)
    inner.put(11, 0)
    inner.put(17, JSONArray().put(JSONArray().put(0)))
    inner.put(18, 0)
    inner.put(27, 1)
    inner.put(30, JSONArray().put(4))
    inner.put(41, JSONArray().put(1))
    inner.put(53, 0)
    inner.put(59, uuidV4().uppercase(Locale.US))
    inner.put(61, JSONArray())
    inner.put(68, 1)
    inner.put(79, 1)
    inner.put(80, 1)
    return inner
}

internal const val INNER_LENGTH = 81

/** 拼 StreamGenerate 的 URL（bl / f.sid 缺失时省略，交给服务端默认）。 */
internal fun buildGeminiUrl(tokens: GeminiTokens, reqId: Long): String {
    val params = mutableListOf<String>()
    if (tokens.bl.isNotBlank()) {
        params += "bl=" + URLEncoder.encode(tokens.bl, "UTF-8")
    }
    if (tokens.sid.isNotBlank()) {
        params += "f.sid=" + URLEncoder.encode(tokens.sid, "UTF-8")
    }
    params += "hl=zh-CN"
    params += "_reqid=$reqId"
    params += "rt=c"
    return STREAM_GENERATE_URL + "?" + params.joinToString("&")
}

internal const val STREAM_GENERATE_URL =
    "https://gemini.google.com/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate"

/** 响应是否算成功：HTTP 2xx、body 非空、不是 HTML 登录页、不含已知错误码。 */
internal fun geminiLooksSuccessful(code: Int, body: String): Boolean {
    if (code !in 200..299) return false
    val text = body.trim()
    if (text.length < 8) return false
    if (looksLikeHtml(text)) return false
    if (geminiErrorText(text) != null) return false
    return true
}

/** 已知 Gemini 错误码/错误文案 → 中文可操作提示。 */
internal fun geminiErrorText(body: String): String? {
    for ((code, message) in GEMINI_ERROR_CODES) {
        if (Regex("(?<![0-9])$code(?![0-9])").containsMatchIn(body)) return message
    }
    val lower = body.lowercase()
    if (lower.contains("consent") || lower.contains("before you continue")) {
        return "Google 要求先同意服务条款：请在浏览器打开 gemini.google.com 完成同意后重试"
    }
    return null
}

private val GEMINI_ERROR_CODES = linkedMapOf(
    1014 to "Gemini 暂时限制了访问（错误码 1014），请稍后重试或更换网络/代理",
    1016 to "Gemini 登录状态无效（错误码 1016），请重新登录并复制 __Secure-1PSID Cookie",
    1037 to "Gemini 消息额度已用尽（错误码 1037），等待额度重置后再试",
    1050 to "Gemini 请求与模型不匹配（错误码 1050），稍后再试",
    1052 to "Gemini 接口参数已变更（错误码 1052），等待适配更新",
    1060 to "Gemini 在当前地区/网络不可用（错误码 1060），请检查代理节点"
)

internal fun geminiFailureText(code: Int, body: String): String {
    geminiErrorText(body)?.let { return it }
    if (code == 401 || code == 403) {
        return "Gemini Cookie 已失效或无权限（HTTP $code），请重新登录 gemini.google.com 后" +
            "复制包含 __Secure-1PSID / __Secure-1PSIDTS 的 Cookie"
    }
    return httpFailureText("Gemini", code, body)
}
