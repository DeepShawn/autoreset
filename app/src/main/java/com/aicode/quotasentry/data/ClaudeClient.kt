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
import kotlin.math.roundToInt

/**
 * Claude（claude.ai 网页版）适配器。
 *
 * 端点（2025-11 用公开逆向实现核实；接口随时可能变，全部失败可解释，不抛异常）：
 * - 身份/组织：`GET https://claude.ai/api/organizations` → 数组，取第一个的 `uuid`；
 *   `capabilities` / `billing_type` 推断 plan。
 * - Claude Code OAuth 额度：`GET https://api.anthropic.com/api/oauth/usage`
 *   （Bearer access token + `anthropic-beta: oauth-2025-04-20`；响应同样是
 *   `five_hour.{utilization 0..100, resets_at RFC3339}`，另有 `seven_day`）。
 *   只要导入 token（`Credentials.accessToken`）即可连接/查额度。
 * - 发送（QQBot 同款，OAuth 优先）：`POST https://api.anthropic.com/v1/messages`
 *   （Bearer + Claude Code 身份头 `anthropic-beta: claude-code-20250219,oauth-2025-04-20`、
 *   `user-agent: claude-cli/2.1.280`、`x-app: cli`，model `claude-haiku-5-5`、max_tokens 32）；
 *   200 即成功。没有 accessToken 时才回退网页 Cookie 发送。
 * - token 刷新：`POST https://platform.claude.com/v1/oauth/token`（429/5xx 依次回退
 *   api.anthropic.com / console.anthropic.com），refresh token 存 `Credentials.extra["refreshToken"]`，
 *   过期时间存 `extra["oauthExpiresAt"]`（提前 5 分钟视为过期）。
 * - 网页版官方额度：`GET https://claude.ai/api/organizations/{org}/usage`
 *   → `five_hour.{utilization,resets_at}`（utilization 0..100，resets_at 为 RFC3339 字符串）；
 *   部分账号/版本还会返回 `seven_day`、`limits[]`（kind/percent/severity）、`standard.rate_limit`。
 *   备用候选：`GET .../rate_limits`（该接口的 resets_at 是 epoch 秒）。
 * - 发送：先 `POST .../chat_conversations` `{"uuid":<uuid4>,"name":""}` 建会话（短消息会话会被复用，
 *   进程重启后重建），再 `POST .../chat_conversations/{uuid}/completion`（SSE，读到第一条 data 帧即成功）。
 *   失败兜底：`POST .../chat_conversations/{uuid}/messages`（旧接口）。
 *
 * 不确定性（注释保留，供后续排障）：
 * - `anthropic-client-*` 指纹头是否必需随账号/地区变化；缺失时可能 403，已在错误文案里提示重新登录。
 * - usage 的量纲在不同时期出现过 0..100 与 0..1 两种（本文件统一按 >1.5 视为百分数）。
 * - 会话复用只是「少建会话」的优化，Claude 侧没有删除会话能力，必要时用户可自行清理历史。
 */
class ClaudeClient : ProviderClient {

    override val id: ProviderId = ProviderId.CLAUDE

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
        val cookie = cookieOf(account)
        val token = tokenOf(account)
        if (cookie.isBlank() && token.isBlank()) {
            return ConnectResult.Fail(
                "未填写 Claude 凭据：请粘贴 claude.ai 的 sessionKey Cookie，" +
                    "或在 Claude Code 登录后导入 OAuth access token"
            )
        }
        val engine = HttpEngine(settings.proxy)

        // 1) OAuth 令牌：优先用它验证并取官方额度（快过期/401 会自动 refresh 一次）
        var oauthPatch: QuotaPatch? = null
        var oauthError: Res.Err? = null
        if (token.isNotBlank()) {
            when (val oauth = fetchOAuthQuota(engine, account)) {
                is Res.Ok -> oauthPatch = oauth.value.second
                is Res.Err -> oauthError = oauth
            }
        }

        // 2) 只有 token（没有 Cookie）：用 OAuth 结果验证连接，发送仍不可用（见 send）
        if (cookie.isBlank()) {
            val patch = oauthPatch
            if (patch != null) {
                if (patch.plan.isNotBlank()) ProviderCache.put(id, KEY_PLAN, patch.plan)
                return ConnectResult.Ok(plan = patch.plan, label = "Claude Code", quota = patch)
            }
            return ConnectResult.Fail(
                oauthError?.message
                    ?: "Claude Code OAuth 令牌无效：请确认 access token 未过期且包含 user:profile 权限"
            )
        }

        // 3) 有 Cookie：保持原有「组织校验」逻辑；官方额度优先用 OAuth 结果
        val org = when (val result = fetchOrganization(engine, account, cookie)) {
            is Res.Err -> return ConnectResult.Fail(result.message)
            is Res.Ok -> result.value
        }
        val patch = oauthPatch ?: when (val quota = fetchUsage(engine, account, cookie, org.uuid)) {
            is Res.Ok -> quota.value
            is Res.Err -> null
        }
        return ConnectResult.Ok(
            plan = org.plan.ifBlank { patch?.plan.orEmpty() },
            label = org.label,
            quota = patch
        )
    }

    private fun fetchOrganization(
        engine: HttpEngine,
        account: Account,
        cookie: String
    ): Res<ClaudeOrg> {
        return when (val result = engine.get(URL_ORGANIZATIONS, headers(account, cookie))) {
            is HttpResult.Fail -> Res.Err(result.message)
            is HttpResult.Ok -> {
                val response = result.response
                if (!response.ok) {
                    return Res.Err(
                        httpFailureText("Claude", response.code, response.body),
                        response.code == 401
                    )
                }
                val org = parseClaudeOrganizations(response.body)
                    ?: return Res.Err("Claude 接口返回无法识别（/api/organizations），可能已变更，请稍后重试")
                ProviderCache.putAll(
                    id,
                    mapOf(
                        KEY_ORG_ID to org.uuid,
                        KEY_PLAN to org.plan,
                        KEY_LABEL to org.label
                    )
                )
                Res.Ok(org)
            }
        }
    }

    // ------------------------------------------------------------------ quota

    private fun fetchQuotaInternal(account: Account, settings: Settings): QuotaFetch {
        val cookie = cookieOf(account)
        val token = tokenOf(account)
        if (cookie.isBlank() && token.isBlank()) {
            return QuotaFetch.Fail(
                "未填写 Claude 凭据：请粘贴 claude.ai Cookie 或导入 Claude Code OAuth 令牌",
                unauthorized = true
            )
        }
        val engine = HttpEngine(settings.proxy)

        // 1) OAuth 优先：只导入 token 也能查官方额度（快过期/401 会自动 refresh 一次）
        var oauthError: Res.Err? = null
        if (token.isNotBlank()) {
            when (val oauth = fetchOAuthQuota(engine, account)) {
                is Res.Ok -> return QuotaFetch.Ok(oauth.value.second)
                is Res.Err -> oauthError = oauth
            }
        }
        if (cookie.isBlank()) {
            val error = oauthError ?: return QuotaFetch.Fail("未填写 Claude 凭据")
            return if (error.unsupported) {
                QuotaFetch.Unsupported(error.message)
            } else {
                QuotaFetch.Fail(error.message, unauthorized = error.unauthorized)
            }
        }

        // 2) 网页 Cookie 接口回退
        val cookieResult: QuotaFetch = when (val org = resolveOrgId(engine, account, cookie)) {
            is Res.Err -> if (org.unsupported) {
                QuotaFetch.Unsupported(org.message)
            } else {
                QuotaFetch.Fail(org.message, unauthorized = org.unauthorized)
            }
            is Res.Ok -> when (val usage = fetchUsage(engine, account, cookie, org.value)) {
                is Res.Ok -> QuotaFetch.Ok(usage.value)
                is Res.Err -> if (usage.unsupported) {
                    QuotaFetch.Unsupported(usage.message)
                } else {
                    QuotaFetch.Fail(usage.message, unauthorized = usage.unauthorized)
                }
            }
        }
        val error = oauthError ?: return cookieResult
        // 3) 两者都失败：合并原因，UI 能看到 OAuth 与 Cookie 各自的问题
        return when (cookieResult) {
            is QuotaFetch.Ok -> cookieResult
            is QuotaFetch.Unsupported -> QuotaFetch.Unsupported(
                "OAuth：${error.message}；Cookie：${cookieResult.message}"
            )
            is QuotaFetch.Fail -> QuotaFetch.Fail(
                "OAuth：${error.message}；Cookie：${cookieResult.message}",
                unauthorized = error.unauthorized || cookieResult.unauthorized
            )
        }
    }

    /** Claude Code OAuth 官方额度（Bearer token，Bearer 头 + anthropic-beta）。 */
    private fun fetchOAuthUsage(engine: HttpEngine, token: String): Res<QuotaPatch> {
        val now = System.currentTimeMillis()
        return when (val result = engine.get(URL_OAUTH_USAGE, claudeOAuthHeaders(token))) {
            is HttpResult.Fail -> Res.Err(result.message)
            is HttpResult.Ok -> {
                val response = result.response
                when {
                    response.ok -> parseClaudeOAuthUsage(response.body, now)
                        ?.let { Res.Ok(it) }
                        ?: Res.Err("Claude Code OAuth 额度接口返回结构无法识别，可能已变更")
                    response.code == 401 -> Res.Err(
                        "Claude Code OAuth 令牌已失效（HTTP 401），请重新登录 Claude Code 并导入最新 access token",
                        unauthorized = true
                    )
                    response.code == 403 -> Res.Err(
                        "Claude Code OAuth 令牌被拒绝（HTTP 403），请确认令牌具有 user:profile 权限",
                        unauthorized = true
                    )
                    response.code == 404 -> Res.Err(
                        "Claude Code OAuth 额度接口不可用（HTTP 404），等待适配更新",
                        unsupported = true
                    )
                    response.code == 429 -> Res.Err(
                        "Claude Code OAuth 额度查询过于频繁（HTTP 429），稍后再试"
                    )
                    else -> Res.Err(httpFailureText("Claude Code", response.code, response.body))
                }
            }
        }
    }

    /**
     * 依次尝试 `/usage` 与 `/rate_limits`，两个都拿不到结构化数据时给出可解释结果。
     * 404 视为「该账号没有官方额度视图」→ Unsupported，让 UI 退回本地估算。
     */
    private fun fetchUsage(
        engine: HttpEngine,
        account: Account,
        cookie: String,
        orgId: String
    ): Res<QuotaPatch> {
        val now = System.currentTimeMillis()
        val usageResult = engine.get(
            "$URL_ORGANIZATIONS/$orgId/usage",
            headers(account, cookie)
        )
        when (usageResult) {
            is HttpResult.Fail -> return Res.Err(usageResult.message)
            is HttpResult.Ok -> {
                val response = usageResult.response
                if (response.ok) {
                    parseClaudeUsage(response.body, now)?.let { return Res.Ok(it) }
                } else if (response.code == 401) {
                    return Res.Err(
                        httpFailureText("Claude", response.code, response.body),
                        unauthorized = true
                    )
                } else if (response.code !in intArrayOf(403, 404)) {
                    return Res.Err(httpFailureText("Claude", response.code, response.body))
                }
            }
        }
        // 备用候选：rate_limits（epoch 秒）
        when (val rateResult = engine.get("$URL_ORGANIZATIONS/$orgId/rate_limits", headers(account, cookie))) {
            is HttpResult.Fail -> return Res.Err(rateResult.message)
            is HttpResult.Ok -> {
                val response = rateResult.response
                if (response.ok) {
                    parseClaudeUsage(response.body, now)?.let { return Res.Ok(it) }
                    return Res.Err("Claude 额度接口返回结构无法识别，可能已变更")
                }
                if (response.code == 404) {
                    return Res.Err(
                        "该 Claude 账号暂未开放官方额度接口（HTTP 404），已改用本地窗口估算",
                        unsupported = true
                    )
                }
                return Res.Err(
                    httpFailureText("Claude", response.code, response.body),
                    unauthorized = response.code == 401
                )
            }
        }
    }

    /** 快过期时先刷新；刷新失败不抛异常，交给后续接口的 401 再判断。 */
    private fun freshenAccount(engine: HttpEngine, account: Account): Account {
        if (!OAuthCredentialStore.isExpiring(
                account,
                System.currentTimeMillis(),
                CLAUDE_REFRESH_MARGIN_MS
            )
        ) {
            return account
        }
        return when (val refreshed = refreshAccountToken(engine, account)) {
            is Res.Ok -> refreshed.value
            is Res.Err -> account
        }
    }

    /** 查 Claude Code OAuth 额度；401/403 时先 refresh 再重试一次。返回 (最新 account, patch)。 */
    private fun fetchOAuthQuota(engine: HttpEngine, account: Account): Res<Pair<Account, QuotaPatch>> {
        var current = freshenAccount(engine, account)
        var result = fetchOAuthUsage(engine, OAuthCredentialStore.accessToken(current))
        if (result is Res.Err && result.unauthorized) {
            when (val refreshed = refreshAccountToken(engine, current)) {
                is Res.Ok -> {
                    current = refreshed.value
                    result = fetchOAuthUsage(engine, OAuthCredentialStore.accessToken(current))
                }
                is Res.Err -> return Res.Err(
                    "${result.message}；刷新失败：${refreshed.message}",
                    unauthorized = true
                )
            }
        }
        return when (result) {
            is Res.Ok -> Res.Ok(current to result.value)
            is Res.Err -> Res.Err(result.message, result.unauthorized, result.unsupported)
        }
    }

    /**
     * 用 refresh token 换新 access token。
     * 主域名 429/5xx 时依次回退备用域名（与 QQBot 的 pi-ai 移植实现一致）；
     * 4xx（refresh token 本身失效）不再重试其它域名。
     */
    private fun refreshAccountToken(engine: HttpEngine, account: Account): Res<Account> {
        val refreshToken = OAuthCredentialStore.refreshToken(account)
        if (refreshToken.isBlank()) {
            return Res.Err("OAuth 令牌已过期且没有 refresh token，请重新登录 Claude Code", unauthorized = true)
        }
        val body = JSONObject()
            .put("grant_type", "refresh_token")
            .put("client_id", CLAUDE_OAUTH_CLIENT_ID)
            .put("refresh_token", refreshToken)
            .toString()
        val headers = mapOf(
            "Content-Type" to HttpEngine.CONTENT_TYPE_JSON,
            "Accept" to "application/json",
            "User-Agent" to HttpEngine.DEFAULT_USER_AGENT
        )
        var lastMessage = ""
        for (url in CLAUDE_TOKEN_URLS) {
            when (val result = engine.postJson(url, headers, body)) {
                is HttpResult.Fail -> {
                    lastMessage = result.message
                    continue
                }
                is HttpResult.Ok -> {
                    val response = result.response
                    if (response.ok) {
                        val tokens = parseOAuthTokenResponse(response.body)
                            ?: return Res.Err("Claude OAuth 刷新响应无法识别，请重新登录")
                        val updated = OAuthCredentialStore.applyTokens(
                            account,
                            tokens,
                            System.currentTimeMillis(),
                            CLAUDE_REFRESH_MARGIN_MS
                        )
                        OAuthCredentialStore.persist(account, updated)
                        return Res.Ok(updated)
                    }
                    lastMessage = httpFailureText("Claude OAuth", response.code, response.body)
                    if (response.code in 400..499 && response.code != 429) {
                        return Res.Err(
                            "Claude OAuth 令牌已失效（HTTP ${response.code}），请重新登录 Claude Code",
                            unauthorized = response.code == 400 ||
                                response.code == 401 ||
                                response.code == 403
                        )
                    }
                }
            }
        }
        return Res.Err("Claude OAuth 刷新失败：${lastMessage.ifBlank { "请检查网络或代理" }}")
    }

    private fun resolveOrgId(
        engine: HttpEngine,
        account: Account,
        cookie: String
    ): Res<String> {
        ProviderCache.resolve(account, KEY_ORG_ID)?.let { return Res.Ok(it) }
        return when (val org = fetchOrganization(engine, account, cookie)) {
            is Res.Err -> Res.Err(org.message, org.unauthorized)
            is Res.Ok -> Res.Ok(org.value.uuid)
        }
    }

    // ------------------------------------------------------------------ send

    private fun sendInternal(
        account: Account,
        settings: Settings,
        message: String
    ): SendOutcome {
        // 1) Claude Code OAuth token：优先走官方 CLI 接口 /v1/messages（QQBot 同款）
        if (OAuthCredentialStore.accessToken(account).isNotBlank()) {
            return sendViaOAuth(account, settings, message)
        }
        // 2) 没有 token 时保留网页 Cookie 发送作为兜底
        val cookie = cookieOf(account)
        if (cookie.isBlank()) {
            val hasToken = tokenOf(account).isNotBlank()
            return SendOutcome(
                ok = false,
                detail = if (hasToken) {
                    "仅导入了 OAuth 令牌；发送保活消息需要 claude.ai 的 sessionKey Cookie，" +
                        "请在账号设置里再粘贴 Cookie"
                } else {
                    "未填写 Claude Cookie，请先在设置里连接账号"
                },
                unauthorized = true
            )
        }
        val engine = HttpEngine(settings.proxy)
        val orgId = when (val org = resolveOrgId(engine, account, cookie)) {
            is Res.Err -> return SendOutcome(false, org.message, org.unauthorized)
            is Res.Ok -> org.value
        }
        val prompt = message.trim().ifEmpty { DefaultMessages.forProvider(ProviderId.CLAUDE) }

        // 1) 复用上次的会话；没有就新建
        var conversationId = ProviderCache.resolve(account, KEY_CONVERSATION_ID)
        var freshConversation = false
        if (conversationId.isNullOrBlank()) {
            when (val created = createConversation(engine, account, cookie, orgId)) {
                is Res.Err -> return SendOutcome(false, created.message, created.unauthorized)
                is Res.Ok -> {
                    conversationId = created.value
                    freshConversation = true
                }
            }
        }

        // 2) completion（SSE），必要时重建会话 / 补 parent uuid 再试
        var attempt = postCompletion(
            engine, account, cookie, orgId, conversationId,
            prompt,
            parentUuid = if (freshConversation) CLAUDE_NULL_PARENT_UUID else null
        )
        if (!attempt.ok && attempt.retryWithNewConversation) {
            ProviderCache.remove(id, KEY_CONVERSATION_ID)
            when (val created = createConversation(engine, account, cookie, orgId)) {
                is Res.Err -> return SendOutcome(false, created.message, created.unauthorized)
                is Res.Ok -> {
                    conversationId = created.value
                    freshConversation = true
                }
            }
            attempt = postCompletion(
                engine, account, cookie, orgId, conversationId,
                prompt,
                parentUuid = if (freshConversation) CLAUDE_NULL_PARENT_UUID else null
            )
        }
        if (!attempt.ok && attempt.retryWithParent) {
            attempt = postCompletion(
                engine, account, cookie, orgId, conversationId,
                prompt,
                parentUuid = CLAUDE_NULL_PARENT_UUID
            )
        }
        if (attempt.ok) {
            ProviderCache.putAll(
                id,
                mapOf(
                    KEY_CONVERSATION_ID to conversationId,
                    "lastSendAt" to System.currentTimeMillis().toString()
                )
            )
            return SendOutcome(ok = true, detail = "Claude 已收到消息（SSE 已返回数据）")
        }
        if (attempt.unauthorized) {
            return SendOutcome(false, attempt.message, unauthorized = true)
        }

        // 3) 旧接口兜底：POST .../messages
        val legacy = postLegacyMessage(engine, account, cookie, orgId, conversationId, prompt)
        if (legacy != null) {
            if (legacy.ok) {
                ProviderCache.put(id, KEY_CONVERSATION_ID, conversationId)
                return SendOutcome(ok = true, detail = "Claude 已收到消息（旧接口兜底）")
            }
            return SendOutcome(false, legacy.message, legacy.unauthorized)
        }
        return SendOutcome(false, attempt.message, attempt.unauthorized)
    }

    /** 用 Claude Code OAuth token 发保活消息（POST /v1/messages）。 */
    private fun sendViaOAuth(account: Account, settings: Settings, message: String): SendOutcome {
        val engine = HttpEngine(settings.proxy)
        val prompt = message.trim().ifEmpty { DefaultMessages.forProvider(ProviderId.CLAUDE) }
        var current = freshenAccount(engine, account)
        var attempt = postClaudeMessages(
            engine,
            OAuthCredentialStore.accessToken(current),
            prompt
        )
        if (!attempt.ok && attempt.unauthorized) {
            when (val refreshed = refreshAccountToken(engine, current)) {
                is Res.Ok -> {
                    current = refreshed.value
                    attempt = postClaudeMessages(
                        engine,
                        OAuthCredentialStore.accessToken(current),
                        prompt
                    )
                }
                is Res.Err -> return SendOutcome(
                    ok = false,
                    detail = "Claude Code OAuth 令牌已失效且刷新失败：${refreshed.message}",
                    unauthorized = true
                )
            }
        }
        return if (attempt.ok) {
            SendOutcome(ok = true, detail = "Claude 已通过 Claude Code OAuth 收到消息")
        } else {
            SendOutcome(ok = false, detail = attempt.message, unauthorized = attempt.unauthorized)
        }
    }

    private fun postClaudeMessages(
        engine: HttpEngine,
        accessToken: String,
        prompt: String
    ): OAuthSendAttempt {
        val body = buildClaudeMessagesBody(prompt)
        val headers = claudeCodeMessagesHeaders(accessToken)
        return when (val result = engine.postJson(CLAUDE_MESSAGES_URL, headers, body)) {
            is HttpResult.Fail -> OAuthSendAttempt(false, result.message)
            is HttpResult.Ok -> {
                val response = result.response
                val failure = claudeMessagesFailure(response.code, response.body)
                if (failure == null) {
                    OAuthSendAttempt(true, "")
                } else {
                    OAuthSendAttempt(
                        ok = false,
                        message = failure,
                        unauthorized = response.code == 401 || response.code == 403
                    )
                }
            }
        }
    }

    private data class OAuthSendAttempt(
        val ok: Boolean,
        val message: String,
        val unauthorized: Boolean = false
    )

    private fun createConversation(
        engine: HttpEngine,
        account: Account,
        cookie: String,
        orgId: String
    ): Res<String> {
        val generated = uuidV4()
        val body = JSONObject()
            .put("uuid", generated)
            .put("name", "")
            .toString()
        val requestHeaders = headers(account, cookie) + mapOf("Content-Type" to HttpEngine.CONTENT_TYPE_JSON)
        return when (val result = engine.postJson("$URL_ORGANIZATIONS/$orgId/chat_conversations", requestHeaders, body)) {
            is HttpResult.Fail -> Res.Err(result.message)
            is HttpResult.Ok -> {
                val response = result.response
                if (!response.ok) {
                    Res.Err(
                        httpFailureText("Claude", response.code, response.body),
                        response.code == 401
                    )
                } else {
                    val returned = parseJsonObjectLenient(response.body)?.optString("uuid").orEmpty()
                    val conversationId = returned.ifBlank { generated }
                    ProviderCache.put(id, KEY_CONVERSATION_ID, conversationId)
                    Res.Ok(conversationId)
                }
            }
        }
    }

    private fun postCompletion(
        engine: HttpEngine,
        account: Account,
        cookie: String,
        orgId: String,
        conversationId: String,
        prompt: String,
        parentUuid: String?
    ): CompletionAttempt {
        val url = "$URL_ORGANIZATIONS/$orgId/chat_conversations/$conversationId/completion"
        val body = JSONObject().apply {
            put("prompt", prompt)
            put("timezone", "Asia/Shanghai")
            put("locale", "zh-CN")
            put("attachments", JSONArray())
            put("files", JSONArray())
            put("rendering_mode", "messages")
            if (parentUuid != null) put("parent_message_uuid", parentUuid)
        }.toString()
        val requestHeaders = headers(account, cookie) + mapOf(
            "Accept" to "text/event-stream",
            "Referer" to "https://claude.ai/new"
        )
        return when (val result = engine.sse(url, requestHeaders, body, maxLines = 12)) {
            is HttpResult.Fail -> CompletionAttempt(false, result.message)
            is HttpResult.Ok -> {
                val response = result.response
                if (response.ok) {
                    val error = sseErrorDetail(response.body)
                    when {
                        error != null -> CompletionAttempt(false, "Claude 拒绝了发送：$error")
                        HttpEngine.sseSampleHasData(response.body) -> CompletionAttempt(true, "")
                        else -> CompletionAttempt(
                            false,
                            "Claude 接口没有返回流数据（可能被风控拦截），请稍后重试"
                        )
                    }
                } else {
                    CompletionAttempt(
                        ok = false,
                        message = httpFailureText("Claude", response.code, response.body),
                        unauthorized = response.code == 401,
                        retryWithNewConversation = response.code == 404,
                        retryWithParent = response.code == 400 && parentUuid == null
                    )
                }
            }
        }
    }

    /** 旧接口兜底：`POST .../chat_conversations/{uuid}/messages`。返回 null 表示不适用（本次无需兜底）。 */
    private fun postLegacyMessage(
        engine: HttpEngine,
        account: Account,
        cookie: String,
        orgId: String,
        conversationId: String,
        prompt: String
    ): CompletionAttempt? {
        val url = "$URL_ORGANIZATIONS/$orgId/chat_conversations/$conversationId/messages"
        val body = JSONObject()
            .put("prompt", prompt)
            .put("timezone", "Asia/Shanghai")
            .toString()
        val requestHeaders = headers(account, cookie) + mapOf("Content-Type" to HttpEngine.CONTENT_TYPE_JSON)
        return when (val result = engine.postJson(url, requestHeaders, body)) {
            is HttpResult.Fail -> CompletionAttempt(false, result.message)
            is HttpResult.Ok -> {
                val response = result.response
                if (response.ok) {
                    CompletionAttempt(true, "")
                } else {
                    CompletionAttempt(
                        ok = false,
                        message = httpFailureText("Claude", response.code, response.body),
                        unauthorized = response.code == 401
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ 辅助

    private fun cookieOf(account: Account): String =
        CredentialUtil.claudeCookie(account.credentials.cookie)

    /** Claude Code OAuth access token：复用 `Credentials.accessToken`（UI 新增的输入框）。 */
    private fun tokenOf(account: Account): String =
        account.credentials.accessToken.trim()

    private fun headers(account: Account, cookie: String): Map<String, String> =
        BrowserHeaders.claude(cookie, deviceId(account, cookie))

    /** anthropic-device-id：优先用 Cookie 里的，其次缓存，最后生成并缓存。 */
    private fun deviceId(account: Account, cookie: String): String {
        CredentialUtil.cookieValue(cookie, "anthropic-device-id")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        ProviderCache.resolve(account, KEY_DEVICE_ID)?.let { return it }
        val generated = uuidV4()
        ProviderCache.put(id, KEY_DEVICE_ID, generated)
        return generated
    }

    private data class CompletionAttempt(
        val ok: Boolean,
        val message: String,
        val unauthorized: Boolean = false,
        val retryWithNewConversation: Boolean = false,
        val retryWithParent: Boolean = false
    )

    private companion object {
        const val URL_ORGANIZATIONS = "https://claude.ai/api/organizations"
        const val URL_OAUTH_USAGE = "https://api.anthropic.com/api/oauth/usage"
        const val KEY_ORG_ID = "orgId"
        const val KEY_PLAN = "plan"
        const val KEY_LABEL = "label"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_CONVERSATION_ID = "conversationId"
    }
}

// ---------------------------------------------------------------------- 纯解析函数（可单测）

/** 组织信息。 */
internal data class ClaudeOrg(val uuid: String, val plan: String, val label: String)

/** `GET /api/organizations` 的容错解析：支持数组、`{organizations:[...]}`、单个对象。 */
internal fun parseClaudeOrganizations(raw: String): ClaudeOrg? {
    val root = parseJsonLenient(raw) ?: return null
    val array: JSONArray = when (root) {
        is JSONArray -> root
        is JSONObject -> root.optJSONArray("organizations")
            ?: root.optJSONArray("data")
            ?: JSONArray().put(root)
        else -> return null
    }
    var org: JSONObject? = null
    for (index in 0 until array.length()) {
        val candidate = array.optJSONObject(index) ?: continue
        if (candidate.optString("uuid").isNotBlank() || candidate.optString("organization_uuid").isNotBlank()) {
            org = candidate
            break
        }
    }
    val target = org ?: return null
    val uuid = target.optString("uuid").ifBlank { target.optString("organization_uuid") }
    if (uuid.isBlank()) return null

    val capabilities = mutableListOf<String>()
    target.optJSONArray("capabilities")?.let { arr ->
        for (index in 0 until arr.length()) {
            arr.optString(index).takeIf { it.isNotBlank() }?.let { capabilities += it }
        }
    }
    target.optJSONObject("capabilities")?.let { obj ->
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (obj.optBoolean(key, false)) capabilities += key
        }
    }
    val billing = target.optString("billing_type")
        .ifBlank { target.optString("billingType") }
        .ifBlank { target.optString("subscription_plan") }
    val plan = inferClaudePlan(capabilities, billing)
    val label = target.optString("name")
        .ifBlank { target.optString("display_name") }
        .ifBlank { target.optString("email") }
    return ClaudeOrg(uuid = uuid, plan = plan, label = label)
}

/** 从 capabilities / billing_type 推断计划名；识别不出来就留空（UI 显示「已连接」即可）。 */
internal fun inferClaudePlan(capabilities: List<String>, billingType: String): String {
    val text = (capabilities.joinToString(" ") + " " + billingType).lowercase()
    return when {
        text.contains("enterprise") -> "Enterprise"
        text.contains("max") -> "Max"
        text.contains("team") -> "Team"
        text.contains("pro") -> "Pro"
        text.contains("free") -> "Free"
        else -> ""
    }
}

/** 一次官方额度解析结果（纯函数便于单测）。 */
internal data class ClaudeUsageResult(
    val usedFraction: Float,
    val windowMillis: Long,
    val resetAt: Long?,
    val detail: String,
    val plan: String = ""
)

/**
 * 容错解析 Claude 额度 JSON（`/usage` 与 `/rate_limits` 两种形态）。
 *
 * 策略：
 * 1. 递归收集所有对象，按 key 路径/kind 关键词挑出「5 小时窗口」与「7 天窗口」；
 * 2. 优先使用 5 小时窗口；没有则退 7 天窗口；再没有就用第一个带 utilization 的对象；
 * 3. 量纲：>1.5 视为百分数；重置时刻支持 ISO8601 / epoch 秒 / 毫秒 / reset_after。
 */
internal fun parseClaudeUsage(raw: String, now: Long): QuotaPatch? {
    val root = parseJsonObjectLenient(raw) ?: return null
    val walked = walkObjects(root)

    fun findWindow(vararg hints: String): WalkedObject? = walked.firstOrNull { walkedObject ->
        hints.any { walkedObject.hint.contains(it, ignoreCase = true) } &&
            readUsedFraction(walkedObject.value) != null
    }

    val five = findWindow("five_hour", "five", "5h", "session", "primary_window", "primary")
    val seven = findWindow("seven_day", "seven", "7d", "weekly", "secondary_window", "secondary")
    val generic = walked.firstOrNull { readUsedFraction(it.value) != null }
    val chosen = five ?: seven ?: generic ?: return null
    val used = readUsedFraction(chosen.value) ?: return null
    val isFive = five != null && chosen === five
    val isSeven = seven != null && chosen === seven

    val defaultWindow = when {
        isFive -> FIVE_HOURS
        isSeven -> SEVEN_DAYS
        chosen.hint.contains("seven") || chosen.hint.contains("weekly") -> SEVEN_DAYS
        else -> FIVE_HOURS
    }
    val window = readWindowMillis(chosen.value) ?: defaultWindow
    val resetAt = readResetAt(chosen.value, now)

    val details = mutableListOf<String>()
    details += when {
        isFive -> "5 小时窗口已用 ${percentText(used)}"
        isSeven -> "7 天窗口已用 ${percentText(used)}（5 小时窗口暂无数据）"
        else -> "已用 ${percentText(used)}"
    }
    if (seven != null && seven !== chosen) {
        readUsedFraction(seven.value)?.let { details += "7 天窗口已用 ${percentText(it)}" }
    }
    // limits[] 里的 severity（normal/warning/critical）顺带展示
    walked.mapNotNull { walkedObject ->
        walkedObject.value.optString("severity").takeIf { it.isNotBlank() && !it.equals("normal", true) }
    }.firstOrNull()?.let { details += "状态：$it" }

    val plan = extractPlanHint(root)
    return QuotaPatch(
        officialUsedFraction = used,
        officialWindowMillis = window,
        officialResetAt = resetAt,
        officialDetail = details.joinToString("；"),
        plan = plan
    )
}

internal fun percentText(fraction: Float): String =
    "${(fraction.coerceIn(0f, 1f) * 100).roundToInt()}%"

internal const val FIVE_HOURS: Long = 5L * 60 * 60 * 1000
internal const val SEVEN_DAYS: Long = 7L * 24 * 60 * 60 * 1000


// ---------------------------------------------------------------------- Claude Code OAuth（可单测）

/** Claude Code OAuth 额度接口的请求头。 */
internal fun claudeOAuthHeaders(token: String): Map<String, String> = mapOf(
    "Authorization" to "Bearer $token",
    "anthropic-beta" to "oauth-2025-04-20",
    "anthropic-version" to "2023-06-01",
    "Accept" to "application/json",
    "User-Agent" to HttpEngine.DEFAULT_USER_AGENT,
    "Accept-Language" to HttpEngine.DEFAULT_ACCEPT_LANGUAGE
)

/**
 * 解析 `GET https://api.anthropic.com/api/oauth/usage` 的响应。
 *
 * 响应形如：
 * ```json
 * {"five_hour":{"utilization":83.0,"resets_at":"2026-06-02T08:30:00.701157+00:00"},
 *  "seven_day":{"utilization":14.0,"resets_at":"..."}}
 * ```
 * - `utilization` 是 0..100 百分数（本文件统一按 >1.5 视为百分数，兼容 0..1 的历史形态）；
 * - `resets_at` 是 RFC3339（支持小数秒与时区偏移）；
 * - 正常路径强制窗口 5h；若只有 seven_day 数据，则退化为 7 天窗口（避免把 7 天用量标成 5 小时）；
 * - 所有字段都可能缺失/为 null，解析失败返回 null，由调用方给中文错误。
 */
internal fun parseClaudeOAuthUsage(raw: String, now: Long): QuotaPatch? {
    val root = parseJsonObjectLenient(raw) ?: return null
    val five = root.optJSONObject("five_hour")
    val seven = root.optJSONObject("seven_day")
    val fiveUsed = five?.let { readUsedFraction(it) }
    val sevenUsed = seven?.let { readUsedFraction(it) }
    val plan = extractPlanHint(root)

    if (fiveUsed == null && sevenUsed == null) {
        // 结构不认识时退回通用解析（仍按 5 小时窗口展示）
        return parseClaudeUsage(raw, now)?.let { it.copy(officialWindowMillis = FIVE_HOURS) }
    }
    val used: Float
    val window: Long
    val resetAt: Long?
    val label: String
    if (fiveUsed != null) {
        used = fiveUsed
        window = FIVE_HOURS
        resetAt = five?.let { readResetAt(it, now) }
        label = "5 小时窗口"
    } else {
        used = sevenUsed ?: return null
        window = SEVEN_DAYS
        resetAt = seven?.let { readResetAt(it, now) }
        label = "7 天窗口"
    }

    val details = mutableListOf<String>()
    details += "${label}已用 ${percentText(used)}"
    if (fiveUsed != null && sevenUsed != null) {
        details += "7 天窗口已用 ${percentText(sevenUsed)}"
    }
    return QuotaPatch(
        officialUsedFraction = used,
        officialWindowMillis = window,
        officialResetAt = resetAt,
        officialDetail = details.joinToString("；"),
        plan = plan
    )
}


// ---------------------------------------------------------------------- Claude Code OAuth / CLI 协议

internal const val CLAUDE_MESSAGES_URL = "https://api.anthropic.com/v1/messages"
internal const val CLAUDE_OAUTH_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
internal const val CLAUDE_ANTHROPIC_VERSION = "2023-06-01"
internal const val CLAUDE_CODE_BETA = "claude-code-20250219,oauth-2025-04-20"
internal const val CLAUDE_CODE_SYSTEM_PROMPT =
    "You are Claude Code, Anthropic's official CLI for Claude."
internal const val CLAUDE_CLI_USER_AGENT = "claude-cli/2.1.280"

/** 与 QQBot / pi-ai 一致：提前 5 分钟视为过期。 */
internal const val CLAUDE_REFRESH_MARGIN_MS = 5L * 60_000L

/** token 端点：platform 域名对部分出口 IP 会 429，因此保留两个备用域名。 */
internal val CLAUDE_TOKEN_URLS = listOf(
    "https://platform.claude.com/v1/oauth/token",
    "https://api.anthropic.com/v1/oauth/token",
    "https://console.anthropic.com/v1/oauth/token"
)

/** 构造 `/v1/messages` 保活请求体（QQBot 同款：haiku + 32 tokens + Claude Code system）。 */
internal fun buildClaudeMessagesBody(prompt: String): String = JSONObject().apply {
    put("model", "claude-haiku-5-5")
    put("max_tokens", 32)
    put(
        "system",
        JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", CLAUDE_CODE_SYSTEM_PROMPT)
        )
    )
    put(
        "messages",
        JSONArray().put(
            JSONObject().apply {
                put("role", "user")
                put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "text")
                            .put("text", prompt)
                    )
                )
            }
        )
    )
}.toString()

/** Claude Code CLI 身份头（OAuth 走订阅通道必须带）。 */
internal fun claudeCodeMessagesHeaders(accessToken: String): Map<String, String> = mapOf(
    "Authorization" to "Bearer $accessToken",
    "anthropic-version" to CLAUDE_ANTHROPIC_VERSION,
    "anthropic-beta" to CLAUDE_CODE_BETA,
    "accept" to "application/json",
    "anthropic-dangerous-direct-browser-access" to "true",
    "content-type" to "application/json",
    "user-agent" to CLAUDE_CLI_USER_AGENT,
    "x-app" to "cli"
)
