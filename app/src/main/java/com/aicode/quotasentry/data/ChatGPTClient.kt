package com.aicode.quotasentry.data

import com.aicode.quotasentry.core.Account
import com.aicode.quotasentry.core.ConnectResult
import com.aicode.quotasentry.core.DefaultMessages
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.QuotaFetch
import com.aicode.quotasentry.core.QuotaPatch
import com.aicode.quotasentry.core.SendOutcome
import com.aicode.quotasentry.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.Random

/**
 * ChatGPT（chatgpt.com 网页版）适配器。
 *
 * 端点（2025-11 用公开逆向实现核实）：
 * - 换 token：`GET https://chatgpt.com/api/auth/session`（Cookie）→ `accessToken`、`user.email`；
 *   也可以直接粘贴 access token（JWT）。plan 从 JWT 的
 *   `https://api.openai.com/auth.chatgpt_plan_type` 或 `wham/usage.plan_type` 读取。
 * - 官方额度：`GET https://chatgpt.com/backend-api/wham/usage`
 *   （Bearer token；`rate_limit.primary_window.used_percent` 0-100、`reset_at` Unix 秒、
 *   `limit_window_seconds` 窗口秒数；`secondary_window` 是 7 天窗口）。
 *   备用候选：`/backend-api/conversation_limits`、`/backend-api/rate_limits`（结构按通用规则解析）。
 * - 发送：`POST https://chatgpt.com/backend-api/conversation`（旧）或
 *   `POST https://chatgpt.com/backend-api/f/conversation`（当前网页版），响应是 SSE。
 *   新版接口可能要求 sentinel 令牌：`POST /backend-api/sentinel/chat-requirements` 拿 token，
 *   如果返回 `proofofwork` 则本地算 proof token（SHA3-512，见 [generateProofToken]）；
 *   `/backend-api/f/conversation/prepare` 可换 `conduit_token`（失败可忽略）。
 *
 * OAuth 方式（QQBot 同款，优先于网页 Cookie）：
 * - 发送：`POST https://chatgpt.com/backend-api/codex/responses`（SSE，Bearer + chatgpt-account-id +
 *   `OpenAI-Beta: responses=experimental` + `originator: pi`），模型 gpt-6.1-sol，
 *   被拒时回退 gpt-5.6-sol / gpt-5.6-luna，仍失败再回退网页 conversation。
 * - 刷新：`POST https://auth.openai.com/oauth/token`（grant_type=refresh_token，client_id
 *   app_EMoamEEZ73f0CkXaXp7hrann），写回 accessToken / extra["refreshToken"] /
 *   extra["oauthExpiresAt"]（expires_in-60s）。accountId 从新 access JWT 重新解析。
 *
 * 不确定性（注释保留）：
 * - 网页版把「对话发送」升级成了带 sentinel/PoW 的流程，且新旧端点并存；
 *   本适配器先用新端点（带 sentinel），失败再回退旧端点，两者都失败才报错。
 * - used_percent 偶有账号返回 0..1 的情况，统一按 >1.5 视为百分数。
 * - 403 常见是 Cloudflare 风控或「Unusual activity」，错误文案会提示用 access token 重试。
 */
class ChatGPTClient : ProviderClient {

    override val id: ProviderId = ProviderId.CHATGPT

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

    override suspend fun fetchQuota(account: Account, settings: Settings): QuotaFetch =
        withContext(Dispatchers.IO) {
            try {
                fetchQuotaInternal(account, settings)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                QuotaFetch.Fail(HttpEngine.networkErrorText(t))
            }
        }

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
        var current = freshenChatGptAccount(account, settings)
        var credential = when (val resolved = resolveCredential(current, settings)) {
            is Res.Err -> return ConnectResult.Fail(resolved.message)
            is Res.Ok -> resolved.value
        }
        var usage = fetchUsage(current, settings, credential)
        if (usage is Res.Err && usage.unauthorized) {
            val refreshed = refreshChatGptOAuthToken(HttpEngine(settings.proxy), current)
            if (refreshed is Res.Ok) {
                current = refreshed.value
                credential = when (val resolved = resolveCredential(current, settings)) {
                    is Res.Err -> credential
                    is Res.Ok -> resolved.value
                }
                usage = fetchUsage(current, settings, credential)
            }
        }
        val claims = parseChatGptClaims(credential.accessToken)
        val planFromToken = claims?.plan.orEmpty()
        val usagePatch = (usage as? Res.Ok)?.value
        val plan = usagePatch?.plan?.takeIf { it.isNotBlank() }
            ?: planFromToken
            ?: planLabel(ProviderCache.resolve(current, KEY_PLAN).orEmpty())
        val label = claims?.email.orEmpty()
            .ifBlank { ProviderCache.resolve(current, KEY_EMAIL).orEmpty() }
        ProviderCache.putAll(
            id,
            mapOf(KEY_PLAN to plan, KEY_EMAIL to label, KEY_ACCOUNT_ID to claims?.accountId)
        )
        return ConnectResult.Ok(plan = plan, label = label, quota = usagePatch)
    }

    // ------------------------------------------------------------------ quota

    private fun fetchQuotaInternal(account: Account, settings: Settings): QuotaFetch {
        var current = freshenChatGptAccount(account, settings)
        var credential = when (val resolved = resolveCredential(current, settings)) {
            is Res.Err -> return if (resolved.unauthorized) {
                QuotaFetch.Fail(resolved.message, unauthorized = true)
            } else {
                QuotaFetch.Fail(resolved.message)
            }
            is Res.Ok -> resolved.value
        }
        var usage = fetchUsage(current, settings, credential)
        if (usage is Res.Err && usage.unauthorized) {
            val refreshed = refreshChatGptOAuthToken(HttpEngine(settings.proxy), current)
            if (refreshed is Res.Ok) {
                current = refreshed.value
                credential = when (val resolved = resolveCredential(current, settings)) {
                    is Res.Err -> credential
                    is Res.Ok -> resolved.value
                }
                usage = fetchUsage(current, settings, credential)
            }
        }
        return when (usage) {
            is Res.Ok -> QuotaFetch.Ok(usage.value)
            is Res.Err -> if (usage.unsupported) {
                QuotaFetch.Unsupported(usage.message)
            } else {
                QuotaFetch.Fail(usage.message, unauthorized = usage.unauthorized)
            }
        }
    }

    /** 依次尝试 wham/usage → conversation_limits → rate_limits，全部不可用时按约定返回 Unsupported。 */
    private fun fetchUsage(
        account: Account,
        settings: Settings,
        credential: ChatCredential
    ): Res<QuotaPatch> {
        val engine = HttpEngine(settings.proxy)
        val now = System.currentTimeMillis()
        val wentThrough = mutableListOf<Int>()
        for (url in USAGE_ENDPOINTS) {
            val headers = BrowserHeaders.chatGpt(credential.cookie, credential.accessToken) +
                accountIdHeader(credential)
            val response = when (val result = engine.get(url, headers)) {
                is HttpResult.Fail -> return Res.Err(result.message)
                is HttpResult.Ok -> result.response
            }
            if (response.ok) {
                val patch = parseChatGptUsage(response.body, now)
                if (patch != null) {
                    patch.plan.takeIf { it.isNotBlank() }?.let { ProviderCache.put(id, KEY_PLAN, it) }
                    return Res.Ok(patch)
                }
                wentThrough += response.code
                continue
            }
            wentThrough += response.code
            if (response.code == 401) {
                return Res.Err(
                    httpFailureText("ChatGPT", response.code, response.body),
                    unauthorized = true
                )
            }
        }
        // 401 已在上面的分支返回；剩下 403/404 视为「没有可用的官方额度接口」
        val unsupported = wentThrough.all { it == 403 || it == 404 || it in 200..299 }
        if (unsupported) {
            val code = wentThrough.firstOrNull() ?: 404
            val hint = if (code == 403) "（HTTP 403，可能未开放或触发了风控）" else "（HTTP 404，接口可能已变更）"
            return Res.Err("ChatGPT 官方额度接口不可用$hint，已改用本地窗口估算", unsupported = true)
        }
        val lastCode = wentThrough.lastOrNull() ?: 0
        return Res.Err("ChatGPT 额度查询失败（HTTP $lastCode），稍后再试")
    }

    // ------------------------------------------------------------------ send

    private fun sendInternal(
        account: Account,
        settings: Settings,
        message: String
    ): SendOutcome {
        // 1) 有 OAuth token：优先走 Codex /responses（QQBot 同款）
        if (OAuthCredentialStore.accessToken(account).isNotBlank()) {
            val codex = sendViaCodex(account, settings, message)
            if (codex.ok) return SendOutcome(true, codex.message)
            // 2) Codex 失败：有 Cookie 时回退网页 conversation 流程
            val (webCookie, _) = CredentialUtil.chatGptCredential(account.credentials.cookie)
            if (webCookie.isBlank()) {
                return SendOutcome(false, codex.message, codex.unauthorized)
            }
            val web = sendViaWeb(account, settings, message)
            if (web.ok) return web
            return SendOutcome(
                ok = false,
                detail = "OAuth：${codex.message}；网页：${web.detail}",
                unauthorized = codex.unauthorized || web.unauthorized
            )
        }
        // 3) 没有 token：保持原来的网页 Cookie 流程
        return sendViaWeb(account, settings, message)
    }

    /** 网页版 conversation 发送（Cookie/sentinel 流程，原有实现）。 */
    private fun sendViaWeb(
        account: Account,
        settings: Settings,
        message: String
    ): SendOutcome {
        var credential = when (val resolved = resolveCredential(account, settings)) {
            is Res.Err -> return SendOutcome(false, resolved.message, resolved.unauthorized)
            is Res.Ok -> resolved.value
        }
        val prompt = message.trim().ifEmpty { DefaultMessages.forProvider(ProviderId.CHATGPT) }
        val engine = HttpEngine(settings.proxy)

        // 1) sentinel 要求（失败不致命，直接尝试旧端点）
        val sentinelHeaders = mutableMapOf<String, String>()
        val requirements = fetchChatRequirements(engine, credential)
        var proofOfWorks = false
        if (requirements != null) {
            requirements.token?.takeIf { it.isNotBlank() }?.let {
                sentinelHeaders["openai-sentinel-chat-requirements-token"] = it
            }
            val proof = requirements.proofOfWork
            if (proof != null && proof.required) {
                generateProofToken(
                    seed = proof.seed,
                    difficulty = proof.difficulty,
                    userAgent = HttpEngine.DEFAULT_USER_AGENT
                )?.let {
                    sentinelHeaders["openai-sentinel-proof-token"] = it
                    proofOfWorks = true
                }
            }
        }

        // 2) 新端点：/backend-api/f/conversation（当前网页版）
        val payload = buildChatGptConversationBody(prompt)
        val conduitToken = fetchConduitToken(engine, credential, payload)
        conduitToken?.let { sentinelHeaders["x-conduit-token"] = it }
        var attempt = postConversation(
            engine, credential, payload, URL_CONVERSATION_NEW, sentinelHeaders
        )

        // 3) token 过期时用 Cookie 重新换一次
        if (!attempt.ok && attempt.unauthorized && credential.cookie.isNotBlank() && !credential.fromPastedToken) {
            val refreshed = refreshToken(engine, credential)
            if (refreshed != null && refreshed.accessToken != credential.accessToken) {
                credential = refreshed
                attempt = postConversation(engine, credential, payload, URL_CONVERSATION_NEW, sentinelHeaders)
            }
        }
        if (attempt.ok) {
            val way = if (proofOfWorks) "，sentinel 校验通过" else ""
            return SendOutcome(true, "ChatGPT 已收到消息（SSE 已返回数据$way）")
        }
        if (attempt.unauthorized) {
            return SendOutcome(false, attempt.message, unauthorized = true)
        }

        // 4) 旧端点兜底：/backend-api/conversation（不需要 sentinel）
        val legacy = postConversation(
            engine, credential, payload, URL_CONVERSATION_LEGACY, emptyMap()
        )
        if (legacy.ok) {
            return SendOutcome(true, "ChatGPT 已收到消息（旧接口兜底）")
        }
        if (legacy.unauthorized) {
            return SendOutcome(false, legacy.message, unauthorized = true)
        }
        return SendOutcome(false, attempt.message.ifBlank { legacy.message })
    }

    /** 快过期时先刷新（失败不抛异常，交给后续 401 判断）。 */
    private fun freshenChatGptAccount(account: Account, settings: Settings): Account {
        if (OAuthCredentialStore.refreshToken(account).isBlank()) return account
        if (!OAuthCredentialStore.isExpiring(
                account,
                System.currentTimeMillis(),
                CHATGPT_REFRESH_MARGIN_MS
            )
        ) {
            return account
        }
        return when (val refreshed = refreshChatGptOAuthToken(HttpEngine(settings.proxy), account)) {
            is Res.Ok -> refreshed.value
            is Res.Err -> account
        }
    }

    /**
     * 用 refresh token 换新 access token（auth.openai.com）。
     * 表单为主（契约要求）；个别部署只吃 JSON body，表单 4xx 时再试一次 JSON。
     */
    private fun refreshChatGptOAuthToken(engine: HttpEngine, account: Account): Res<Account> {
        val refreshToken = OAuthCredentialStore.refreshToken(account)
        if (refreshToken.isBlank()) {
            return Res.Err("OAuth 令牌已过期且没有 refresh token，请重新登录 ChatGPT", unauthorized = true)
        }
        val fields = mapOf(
            "grant_type" to "refresh_token",
            "client_id" to OPENAI_OAUTH_CLIENT_ID,
            "refresh_token" to refreshToken
        )
        val headers = mapOf(
            "Accept" to "application/json",
            "User-Agent" to HttpEngine.DEFAULT_USER_AGENT
        )
        var response = when (val result = engine.postForm(CHATGPT_OAUTH_TOKEN_URL, headers, fields)) {
            is HttpResult.Fail -> return Res.Err(result.message)
            is HttpResult.Ok -> result.response
        }
        if (!response.ok && response.code in intArrayOf(400, 401, 415, 422)) {
            val jsonBody = JSONObject()
                .put("grant_type", "refresh_token")
                .put("client_id", OPENAI_OAUTH_CLIENT_ID)
                .put("refresh_token", refreshToken)
                .toString()
            val jsonHeaders = headers + mapOf("Content-Type" to HttpEngine.CONTENT_TYPE_JSON)
            response = when (val result = engine.postJson(CHATGPT_OAUTH_TOKEN_URL, jsonHeaders, jsonBody)) {
                is HttpResult.Fail -> return Res.Err(result.message)
                is HttpResult.Ok -> result.response
            }
        }
        if (!response.ok) {
            return Res.Err(
                if (response.code == 429) {
                    "ChatGPT OAuth 刷新过于频繁（HTTP 429），稍后再试"
                } else {
                    httpFailureText("ChatGPT OAuth", response.code, response.body)
                },
                unauthorized = response.code in intArrayOf(400, 401, 403)
            )
        }
        val tokens = parseOAuthTokenResponse(response.body)
            ?: return Res.Err("ChatGPT OAuth 刷新响应无法识别，请重新登录")
        val claims = parseChatGptClaims(tokens.accessToken)
        val extra = buildMap {
            claims?.accountId?.takeIf { it.isNotBlank() }?.let {
                put(OAuthCredentialStore.KEY_ACCOUNT_ID, it)
            }
            claims?.plan?.takeIf { it.isNotBlank() }?.let { put(KEY_PLAN, it) }
            claims?.email?.takeIf { it.isNotBlank() }?.let { put(KEY_EMAIL, it) }
        }
        val updated = OAuthCredentialStore.applyTokens(
            account,
            tokens,
            System.currentTimeMillis(),
            CHATGPT_REFRESH_MARGIN_MS,
            extra
        )
        OAuthCredentialStore.persist(account, updated)
        return Res.Ok(updated)
    }

    /** 用 Codex OAuth token 发保活消息；模型被拒自动回退，401/403 先刷新再重试一次。 */
    private fun sendViaCodex(account: Account, settings: Settings, message: String): CodexAttempt {
        val prompt = message.trim().ifEmpty { DefaultMessages.forProvider(ProviderId.CHATGPT) }
        val engine = HttpEngine(settings.proxy)
        var current = freshenChatGptAccount(account, settings)
        var attempt = attemptCodexAllModels(engine, current, prompt)
        if (!attempt.ok && attempt.unauthorized) {
            when (val refreshed = refreshChatGptOAuthToken(engine, current)) {
                is Res.Ok -> {
                    current = refreshed.value
                    attempt = attemptCodexAllModels(engine, current, prompt)
                }
                is Res.Err -> return CodexAttempt(
                    ok = false,
                    message = "ChatGPT OAuth 令牌已失效且刷新失败：${refreshed.message}",
                    unauthorized = true
                )
            }
        }
        return attempt
    }

    private fun attemptCodexAllModels(
        engine: HttpEngine,
        account: Account,
        prompt: String
    ): CodexAttempt {
        val accessToken = OAuthCredentialStore.accessToken(account)
        if (accessToken.isBlank()) {
            return CodexAttempt(false, "缺少 ChatGPT OAuth access token", unauthorized = true)
        }
        val claims = parseChatGptClaims(accessToken)
        val accountId = account.credentials.extra[OAuthCredentialStore.KEY_ACCOUNT_ID]
            ?.takeIf { it.isNotBlank() }
            ?: claims?.accountId?.takeIf { it.isNotBlank() }
            ?: return CodexAttempt(false, "ChatGPT 账号信息不完整（缺少 account id），请重新登录")
        var last = CodexAttempt(false, "ChatGPT Codex 接口未返回结果")
        for (model in CODEX_MODEL_FALLBACKS) {
            val attempt = postCodexResponses(engine, accessToken, accountId, model, prompt)
            if (attempt.ok) return attempt
            last = attempt
            if (attempt.unauthorized) return attempt
            // 只有「模型被拒」才换下一个模型；其它错误直接返回
            if (!attempt.modelRejected) return attempt
        }
        return last
    }

    private fun postCodexResponses(
        engine: HttpEngine,
        accessToken: String,
        accountId: String,
        model: String,
        prompt: String
    ): CodexAttempt {
        val body = buildCodexResponsesBody(model, prompt)
        val headers = codexResponsesHeaders(accessToken, accountId)
        return when (val result = engine.sse(CODEX_RESPONSES_URL, headers, body, maxLines = 12)) {
            is HttpResult.Fail -> CodexAttempt(false, result.message)
            is HttpResult.Ok -> {
                val response = result.response
                if (response.ok) {
                    val sseError = chatGptSseError(response.body)
                    when {
                        sseError != null -> CodexAttempt(
                            ok = false,
                            message = "ChatGPT Codex 拒绝了请求：$sseError",
                            model = model,
                            modelRejected = isModelRejected(400, sseError)
                        )
                        HttpEngine.sseSampleHasData(response.body) -> CodexAttempt(true, "", model = model)
                        else -> CodexAttempt(
                            ok = false,
                            message = "ChatGPT Codex 接口没有返回流数据（可能被风控拦截）",
                            model = model
                        )
                    }
                } else {
                    val detail = apiErrorDetail(response.body)
                    val message = when (response.code) {
                        429 -> "ChatGPT 额度用尽或请求过于频繁（HTTP 429），稍后再试"
                        else -> detail?.let { "ChatGPT Codex 请求失败（HTTP ${response.code}）：$it" }
                            ?: chatGptFailureText(response.code, response.body)
                    }
                    CodexAttempt(
                        ok = false,
                        message = message,
                        unauthorized = response.code == 401 || response.code == 403,
                        model = model,
                        modelRejected = isModelRejected(response.code, response.body)
                    )
                }
            }
        }
    }

    private data class CodexAttempt(
        val ok: Boolean,
        val message: String,
        val unauthorized: Boolean = false,
        val model: String = "",
        val modelRejected: Boolean = false
    )

    private fun postConversation(
        engine: HttpEngine,
        credential: ChatCredential,
        payload: String,
        endpoint: String,
        extraHeaders: Map<String, String>
    ): ChatSendAttempt {
        val headers = BrowserHeaders.chatGpt(credential.cookie, credential.accessToken, accept = "text/event-stream") +
            accountIdHeader(credential) +
            extraHeaders +
            mapOf("Content-Type" to HttpEngine.CONTENT_TYPE_JSON)
        return when (val result = engine.sse(endpoint, headers, payload, maxLines = 12)) {
            is HttpResult.Fail -> ChatSendAttempt(false, result.message, 0, false)
            is HttpResult.Ok -> {
                val response = result.response
                if (response.ok) {
                    val error = sseErrorDetail(response.body)
                    when {
                        error != null -> ChatSendAttempt(false, "ChatGPT 拒绝了发送：$error", response.code, false)
                        HttpEngine.sseSampleHasData(response.body) -> ChatSendAttempt(true, "", response.code, false)
                        else -> ChatSendAttempt(
                            false,
                            "ChatGPT 接口没有返回流数据（可能被风控拦截），请稍后重试",
                            response.code,
                            false
                        )
                    }
                } else {
                    ChatSendAttempt(
                        ok = false,
                        message = chatGptFailureText(response.code, response.body),
                        code = response.code,
                        unauthorized = response.code == 401
                    )
                }
            }
        }
    }

    private fun fetchChatRequirements(engine: HttpEngine, credential: ChatCredential): ChatRequirements? {
        val headers = BrowserHeaders.chatGpt(credential.cookie, credential.accessToken) +
            accountIdHeader(credential) +
            mapOf("Content-Type" to HttpEngine.CONTENT_TYPE_JSON)
        return when (val result = engine.postJson(URL_CHAT_REQUIREMENTS, headers, """{"p":null}""")) {
            is HttpResult.Fail -> null
            is HttpResult.Ok -> if (result.response.ok) parseChatRequirements(result.response.body) else null
        }
    }

    private fun fetchConduitToken(
        engine: HttpEngine,
        credential: ChatCredential,
        payload: String
    ): String? {
        val headers = BrowserHeaders.chatGpt(credential.cookie, credential.accessToken) +
            accountIdHeader(credential) +
            mapOf("Content-Type" to HttpEngine.CONTENT_TYPE_JSON)
        return when (val result = engine.postJson(URL_PREPARE, headers, payload)) {
            is HttpResult.Fail -> null
            is HttpResult.Ok -> if (result.response.ok) {
                parseJsonObjectLenient(result.response.body)?.optString("conduit_token")?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    }

    private fun refreshToken(engine: HttpEngine, credential: ChatCredential): ChatCredential? {
        if (credential.cookie.isBlank()) return null
        val headers = BrowserHeaders.chatGpt(credential.cookie, null)
        val response = when (val result = engine.get(URL_SESSION, headers)) {
            is HttpResult.Fail -> return null
            is HttpResult.Ok -> result.response
        }
        if (!response.ok) return null
        val session = parseChatGptSession(response.body) ?: return null
        ProviderCache.put(id, KEY_ACCESS_TOKEN, session.accessToken)
        return ChatCredential(
            cookie = credential.cookie,
            accessToken = session.accessToken,
            fromPastedToken = false
        )
    }

    // ------------------------------------------------------------------ 凭据

    private fun resolveCredential(account: Account, settings: Settings): Res<ChatCredential> {
        val rawCookie = account.credentials.cookie
        val (cookieFromRaw, tokenFromRaw) = CredentialUtil.chatGptCredential(rawCookie)
        val cookie = cookieFromRaw
        val explicitToken = account.credentials.accessToken.trim()
        var accessToken = explicitToken
            .ifBlank { tokenFromRaw }
            .ifBlank { ProviderCache.resolve(account, KEY_ACCESS_TOKEN).orEmpty() }
        if (cookie.isBlank() && accessToken.isBlank()) {
            return Res.Err(
                "未填写 ChatGPT 凭据：请粘贴包含 __Secure-next-auth.session-token 的 Cookie，" +
                    "或直接粘贴 access token",
                unauthorized = true
            )
        }
        if (accessToken.isBlank()) {
            val engine = HttpEngine(settings.proxy)
            val response = when (val result = engine.get(URL_SESSION, BrowserHeaders.chatGpt(cookie, null))) {
                is HttpResult.Fail -> return Res.Err(result.message)
                is HttpResult.Ok -> result.response
            }
            if (!response.ok) {
                return Res.Err(
                    chatGptFailureText(response.code, response.body),
                    unauthorized = response.code == 401 || response.code == 403
                )
            }
            val session = parseChatGptSession(response.body)
                ?: return Res.Err(
                    "无法从 ChatGPT Cookie 换取 access token（可能未登录或 Cookie 不完整），" +
                        "请在浏览器打开 chatgpt.com 后重新复制 Cookie",
                    unauthorized = true
                )
            accessToken = session.accessToken
            ProviderCache.put(id, KEY_ACCESS_TOKEN, accessToken)
            session.email.takeIf { it.isNotBlank() }?.let { ProviderCache.put(id, KEY_EMAIL, it) }
        }
        return Res.Ok(
            ChatCredential(
                cookie = cookie,
                accessToken = accessToken,
                fromPastedToken = cookie.isBlank()
            )
        )
    }

    private fun accountIdHeader(credential: ChatCredential): Map<String, String> {
        val accountId = credential.accountId
            ?: parseChatGptClaims(credential.accessToken)?.accountId?.takeIf { it.isNotBlank() }
            ?: return emptyMap()
        return mapOf("ChatGPT-Account-Id" to accountId)
    }

    private data class ChatCredential(
        val cookie: String,
        val accessToken: String,
        val fromPastedToken: Boolean,
        val accountId: String? = null
    )

    private data class ChatSendAttempt(
        val ok: Boolean,
        val message: String,
        val code: Int,
        val unauthorized: Boolean
    )

    private companion object {
        const val URL_SESSION = "https://chatgpt.com/api/auth/session"
        const val URL_WHAM_USAGE = "https://chatgpt.com/backend-api/wham/usage"
        const val URL_CONVERSATION_LIMITS = "https://chatgpt.com/backend-api/conversation_limits"
        const val URL_RATE_LIMITS = "https://chatgpt.com/backend-api/rate_limits"
        const val URL_CONVERSATION_NEW = "https://chatgpt.com/backend-api/f/conversation"
        const val URL_CONVERSATION_LEGACY = "https://chatgpt.com/backend-api/conversation"
        const val URL_PREPARE = "https://chatgpt.com/backend-api/f/conversation/prepare"
        const val URL_CHAT_REQUIREMENTS = "https://chatgpt.com/backend-api/sentinel/chat-requirements"
        val USAGE_ENDPOINTS = listOf(URL_WHAM_USAGE, URL_CONVERSATION_LIMITS, URL_RATE_LIMITS)
        const val KEY_ACCESS_TOKEN = "accessToken"
        const val KEY_ACCOUNT_ID = "accountId"
        const val KEY_PLAN = "plan"
        const val KEY_EMAIL = "email"
    }
}

/** ChatGPT 错误文案：在通用文案基础上补充服务端 `detail` 与风控提示。 */
internal fun chatGptFailureText(code: Int, body: String): String {
    if (code == 403) {
        val detail = extractErrorDetail(body)
        if (body.contains("Unusual activity", ignoreCase = true) ||
            body.contains("sentinel", ignoreCase = true) ||
            body.contains("proof", ignoreCase = true)
        ) {
            return "ChatGPT 触发了人机验证（403）：多半是代理/VPN 的 IP 被 Cloudflare 风控。" +
                "请换一个干净的节点，或在浏览器打开 chatgpt.com 正常对话一次后，重新粘贴**包含 cf_clearance 的完整 Cookie**" +
                "（只有 accessToken 不够）" + if (detail.isNotBlank()) "。服务端：$detail" else ""
        }
        if (looksLikeHtml(body) || looksLikeCloudflareChallenge(body)) {
            return httpFailureText("ChatGPT", code, body)
        }
        return "ChatGPT 拒绝了请求（HTTP 403）：Cookie/access token 可能过期或账号触发风控" +
            if (detail.isNotBlank()) "（$detail）" else ""
    }
    if (code == 429) {
        return "ChatGPT 额度已用尽或请求过于频繁（HTTP 429），等待额度重置后再试"
    }
    return httpFailureText("ChatGPT", code, body)
}

internal fun extractErrorDetail(body: String): String {
    val json = parseJsonObjectLenient(body) ?: return ""
    val detail = json.opt("detail")
    if (detail is String) return detail.take(160)
    if (detail is JSONObject) return detail.optString("message").take(160)
    return json.optString("error").take(160)
}

// ---------------------------------------------------------------------- 纯解析函数（可单测）

internal data class ChatGptSession(
    val accessToken: String,
    val email: String,
    val plan: String
)

/** `GET /api/auth/session` 的容错解析。 */
internal fun parseChatGptSession(raw: String): ChatGptSession? {
    val root = parseJsonObjectLenient(raw) ?: return null
    val token = root.optString("accessToken")
        .ifBlank { root.optString("access_token") }
        .trim()
    if (token.isBlank()) return null
    val user = root.optJSONObject("user")
    val email = user?.optString("email")
        ?: root.optString("email")
    val planRaw = user?.optString("plan")
        ?: user?.optString("plan_type")
        ?: root.optString("plan_type")
        ?: root.optString("plan")
    val plan = planLabel(planRaw).ifBlank { parseChatGptClaims(token)?.plan.orEmpty() }
    return ChatGptSession(accessToken = token, email = email.orEmpty(), plan = plan)
}

internal data class ChatGptClaims(
    val plan: String = "",
    val accountId: String = "",
    val email: String = ""
)

/** 解析 access token（JWT）里的 plan / account id / 邮箱，不做签名校验（只读取自己的 token）。 */
internal fun parseChatGptClaims(jwt: String?): ChatGptClaims? {
    val token = jwt?.trim().orEmpty()
    val parts = token.split('.')
    if (parts.size < 2) return null
    val payload = decodeBase64Url(parts[1]) ?: return null
    val root = parseJsonObjectLenient(payload) ?: return null
    val auth = root.optJSONObject("https://api.openai.com/auth")
    val profile = root.optJSONObject("https://api.openai.com/profile")
    val plan = planLabel(auth?.optString("chatgpt_plan_type").orEmpty())
    val accountId = auth?.optString("chatgpt_account_id").orEmpty()
    val email = root.optString("email").ifBlank { profile?.optString("email").orEmpty() }
    if (plan.isBlank() && accountId.isBlank() && email.isBlank()) return null
    return ChatGptClaims(plan = plan, accountId = accountId, email = email)
}

internal fun decodeBase64Url(raw: String): String? {
    val padded = buildString {
        append(raw.trim())
        while (length % 4 != 0) append('=')
    }
    return runCatching {
        Base64.getUrlDecoder().decode(padded).toString(Charsets.UTF_8)
    }.getOrNull()
}

/**
 * ChatGPT 额度 JSON 的容错解析：
 * 1. 优先 `rate_limit.primary_window`（wham/usage，0-100 百分数 + Unix 秒 reset）；
 * 2. 其次 `limits[]` / `rate_limits[]` 里的 used/limit、remaining/limit；
 * 3. 再递归找第一个带 utilization/percent 的对象。
 */
internal fun parseChatGptUsage(raw: String, now: Long): QuotaPatch? {
    val root = parseJsonObjectLenient(raw) ?: return null
    val plan = extractPlanHint(root)
    val walked = walkObjects(root)

    val rateLimit = root.optJSONObject("rate_limit")
        ?: root.optJSONObject("rate_limits")
        ?: walked.firstOrNull { it.hint.contains("rate_limit") }?.value
    val primary = rateLimit?.optJSONObject("primary_window")
        ?: walked.firstOrNull {
            it.hint.contains("primary_window") || it.hint.contains("primary")
        }?.value
    val secondary = rateLimit?.optJSONObject("secondary_window")
        ?: walked.firstOrNull {
            it.hint.contains("secondary_window") || it.hint.contains("secondary")
        }?.value

    var used: Float? = null
    var window: Long? = null
    var resetAt: Long? = null
    var primaryLabel = "5 小时窗口"

    if (primary != null) {
        used = readUsedFraction(primary)
        window = readWindowMillis(primary)
        resetAt = readResetAt(primary, now)
    }
    if (used == null) {
        val entry = findChatGptUsageEntry(walked, now)
        if (entry != null) {
            used = entry.used
            window = entry.window
            resetAt = entry.resetAt
            primaryLabel = entry.label
        }
    }
    if (used == null && resetAt == null) return null

    val details = mutableListOf<String>()
    if (used != null) details += "${primaryLabel}已用 ${percentText(used)}"
    if (secondary != null) {
        readUsedFraction(secondary)?.let { details += "7 天窗口已用 ${percentText(it)}" }
    }
    if (used == null) details += "额度窗口重置时间已知，用量暂不可读"

    return QuotaPatch(
        officialUsedFraction = used,
        officialWindowMillis = window ?: FIVE_HOURS,
        officialResetAt = resetAt,
        officialDetail = details.joinToString("；"),
        plan = plan
    )
}

private data class ChatGptUsageEntry(
    val used: Float?,
    val window: Long?,
    val resetAt: Long?,
    val label: String
)

private fun findChatGptUsageEntry(walked: List<WalkedObject>, now: Long): ChatGptUsageEntry? {
    // 先找带 used/limit 或 remaining/limit 的窗口条目
    for (walkedObject in walked) {
        val obj = walkedObject.value
        val window = usageWindowOf(obj) ?: continue
        val windowMillis = readWindowMillis(obj)
        val resetAt = readResetAt(obj, now)
        val label = when {
            walkedObject.hint.contains("primary") -> "5 小时窗口"
            walkedObject.hint.contains("secondary") -> "7 天窗口"
            walkedObject.hint.contains("five") || walkedObject.hint.contains("5h") -> "5 小时窗口"
            walkedObject.hint.contains("seven") || walkedObject.hint.contains("7d") -> "7 天窗口"
            else -> "额度窗口"
        }
        return ChatGptUsageEntry(window, windowMillis, resetAt, label)
    }
    // 再退一步：任何带 utilization 的对象
    for (walkedObject in walked) {
        val used = readUsedFraction(walkedObject.value) ?: continue
        return ChatGptUsageEntry(
            used = used,
            window = readWindowMillis(walkedObject.value),
            resetAt = readResetAt(walkedObject.value, now),
            label = "额度窗口"
        )
    }
    return null
}

/** 从对象里读出 used/limit 或 remaining/limit → 0..1 比例。 */
private fun usageWindowOf(obj: JSONObject): Float? {
    readUsedFraction(obj)?.let { return it }
    val limit = obj.optDouble("limit", Double.NaN)
        .takeIf { it.isFinite() && it > 0 }
        ?: obj.optDouble("total", Double.NaN).takeIf { it.isFinite() && it > 0 }
        ?: return null
    val used = obj.optDouble("used", Double.NaN)
    if (used.isFinite() && used >= 0) return (used / limit).coerceIn(0.0, 1.0).toFloat()
    val remaining = obj.optDouble("remaining", Double.NaN)
    if (remaining.isFinite() && remaining >= 0) {
        return (1.0 - remaining / limit).coerceIn(0.0, 1.0).toFloat()
    }
    return null
}

internal data class ChatProofOfWork(
    val required: Boolean,
    val seed: String,
    val difficulty: String
)

internal data class ChatRequirements(
    val token: String?,
    val proofOfWork: ChatProofOfWork?,
    val turnstileRequired: Boolean = false
)

/** `POST /backend-api/sentinel/chat-requirements` 的容错解析。 */
internal fun parseChatRequirements(raw: String): ChatRequirements? {
    val root = parseJsonObjectLenient(raw) ?: return null
    val token = root.optString("token").takeIf { it.isNotBlank() }
    val pow = root.optJSONObject("proofofwork")
    val proofOfWork = pow?.let {
        ChatProofOfWork(
            required = it.optBoolean("required", true),
            seed = it.optString("seed"),
            difficulty = it.optString("difficulty")
        )
    }
    val turnstile = root.optJSONObject("turnstile")?.optBoolean("required", false) ?: false
    if (token == null && proofOfWork == null) return null
    return ChatRequirements(token = token, proofOfWork = proofOfWork, turnstileRequired = turnstile)
}

/** 构造 ChatGPT 发送请求体（契约里的最小可用形态）。 */
internal fun buildChatGptConversationBody(message: String): String {
    val content = JSONObject()
        .put("content_type", "text")
        .put("parts", JSONArray().put(message))
    val messageObject = JSONObject()
        .put("id", uuidV4())
        .put("author", JSONObject().put("role", "user"))
        .put("content", content)
        .put("metadata", JSONObject())
    return JSONObject()
        .put("action", "next")
        .put("messages", JSONArray().put(messageObject))
        .put("parent_message_id", "client-created-root")
        .put("model", "auto")
        .put("timezone_offset_min", -480)
        .put("history_and_training_disabled", true)
        .put("conversation_mode", JSONObject().put("kind", "primary_assistant"))
        .put("force_paragen", false)
        .put("force_rate_limit", false)
        .toString()
}

/**
 * 生成 ChatGPT 的 proof-of-work token：`"gAAAAAB" + base64(pythonJson(proofToken))`，
 * 其中 proofToken[3] 递增，直到 `SHA3-512(seed + base64)` 的十六进制前缀字典序 ≤ difficulty。
 *
 * 注意：这里刻意复刻 Python `json.dumps` 的默认分隔符（", "）与非 ASCII 转义，
 * 因为哈希是对「Python 序列化后的字符串」做的；换成 org.json 的紧凑输出会导致校验失败。
 * 属 best-effort：服务端若升级算法，发送会失败并给出可解释文案。
 */
internal fun generateProofToken(
    seed: String,
    difficulty: String,
    userAgent: String,
    maxAttempts: Int = 100_000,
    random: Random = Random()
): String? {
    if (difficulty.isBlank()) return null
    val proofToken = buildProofTokenArray(userAgent, random)
    val difficultyLength = difficulty.length
    for (attempt in 0 until maxAttempts) {
        proofToken[3] = attempt
        val base = Base64.getEncoder()
            .encodeToString(pythonJson(proofToken).toByteArray(Charsets.UTF_8))
        val hash = Sha3.toHex(Sha3.sha512((seed + base).toByteArray(Charsets.UTF_8)))
        if (hash.take(difficultyLength) <= difficulty) {
            return "gAAAAAB$base"
        }
    }
    return null
}

private fun buildProofTokenArray(userAgent: String, random: Random): MutableList<Any?> {
    val screen = PROOF_SCREENS[random.nextInt(PROOF_SCREENS.size)] *
        PROOF_SCREEN_MULTIPLIERS[random.nextInt(PROOF_SCREEN_MULTIPLIERS.size)]
    val timeText = DateTimeFormatter
        .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
        .format(ZonedDateTime.now(ZoneOffset.UTC))
    return mutableListOf(
        screen,
        timeText,
        null,
        0,
        userAgent,
        "https://tcr9i.chat.openai.com/v2/35536E1E-65B4-4D96-9D97-6ADB7EFF8147/api.js",
        "dpl=1440a687921de39ff5ee56b92807faaadce73f13",
        "en",
        "en-US",
        null,
        // 与逆向实现一致：这里是一个 U+2212（减号）而不是普通连字符
        "plugins\u2212[object PluginArray]",
        PROOF_REACT_KEYS[random.nextInt(PROOF_REACT_KEYS.size)],
        PROOF_EVENTS[random.nextInt(PROOF_EVENTS.size)]
    )
}

/** 复刻 Python `json.dumps(value)` 的默认行为（ensure_ascii=True、分隔符 ", "）。 */
internal fun pythonJson(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> if (value) "true" else "false"
    is Number -> value.toString()
    is String -> pythonJsonString(value)
    is List<*> -> value.joinToString(separator = ", ", prefix = "[", postfix = "]") { pythonJson(it) }
    else -> pythonJsonString(value.toString())
}

internal fun pythonJsonString(value: String): String = buildString {
    append('"')
    for (char in value) {
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char in ' '..'~' -> append(char)
            else -> append("\\u%04x".format(char.code))
        }
    }
    append('"')
}

private val PROOF_SCREENS = intArrayOf(3008, 4010, 6000)
private val PROOF_SCREEN_MULTIPLIERS = intArrayOf(1, 2, 4)
private val PROOF_REACT_KEYS = listOf(
    "_reactListeningcfilawjnerp",
    "_reactListening9ne2dfo1i47",
    "_reactListening410nzwhan2a"
)
private val PROOF_EVENTS = listOf("alert", "ontransitionend", "onprogress")


// ---------------------------------------------------------------------- ChatGPT Codex OAuth 协议

internal const val CODEX_RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses"
internal const val CHATGPT_OAUTH_TOKEN_URL = "https://auth.openai.com/oauth/token"
internal const val OPENAI_OAUTH_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
internal const val CODEX_ORIGINATOR = "pi"

/** 与 QQBot / pi-ai 一致：提前 60 秒视为过期。 */
internal const val CHATGPT_REFRESH_MARGIN_MS = 60L * 1000L

/** 契约要求的模型回退顺序：主模型被拒时依次降级。 */
internal val CODEX_MODEL_FALLBACKS = listOf("gpt-6.1-sol", "gpt-5.6-sol", "gpt-5.6-luna")

/** 构造 Codex `/responses` 保活请求体（契约给定形态）。 */
internal fun buildCodexResponsesBody(model: String, message: String): String = JSONObject().apply {
    put("model", model)
    put("store", false)
    put("stream", true)
    put("instructions", "You are a helpful assistant.")
    put(
        "input",
        JSONArray().put(
            JSONObject().apply {
                put("type", "message")
                put("role", "user")
                put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "input_text")
                            .put("text", message)
                    )
                )
            }
        )
    )
    put("text", JSONObject().put("verbosity", "low"))
    put("include", JSONArray().put("reasoning.encrypted_content"))
    put("tool_choice", "auto")
    put("parallel_tool_calls", true)
}.toString()

/** Codex OAuth 请求头（account id 从 extra 或 access JWT 取）。 */
internal fun codexResponsesHeaders(accessToken: String, accountId: String): Map<String, String> = mapOf(
    "Authorization" to "Bearer $accessToken",
    "chatgpt-account-id" to accountId,
    "OpenAI-Beta" to "responses=experimental",
    "originator" to CODEX_ORIGINATOR,
    "accept" to "text/event-stream",
    "content-type" to "application/json",
    "User-Agent" to HttpEngine.DEFAULT_USER_AGENT
)

/** 判断错误是否属于「当前模型不可用」，用于切换回退模型。 */
internal fun isModelRejected(code: Int, body: String): Boolean {
    if (code != 400 && code != 404) return false
    val lower = body.lowercase()
    val mentionsModel = lower.contains("model")
    val rejection = lower.contains("not found") ||
        lower.contains("unsupported") ||
        lower.contains("not supported") ||
        lower.contains("does not exist") ||
        lower.contains("unknown model") ||
        lower.contains("invalid model") ||
        lower.contains("model_not_found")
    return mentionsModel && rejection
}
