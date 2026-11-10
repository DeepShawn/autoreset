package com.aicode.quotasentry.auth

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aicode.quotasentry.core.ConnectResult
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.Store
import com.aicode.quotasentry.data.HttpEngine
import com.aicode.quotasentry.data.HttpResult
import com.aicode.quotasentry.data.Providers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

sealed interface OAuthState {
    data object Idle : OAuthState

    /** 已在 Chrome 打开授权页，等待用户完成登录。 */
    data class Waiting(val provider: ProviderId, val hint: String) : OAuthState

    /** ChatGPT 设备码登录：在浏览器里输入 userCode。 */
    data class DeviceCode(
        val provider: ProviderId,
        val userCode: String,
        val verificationUrl: String,
        val hint: String
    ) : OAuthState

    /** 已拿到授权码，正在换 token / 验证账号。 */
    data class Exchanging(val provider: ProviderId) : OAuthState

    data class Success(val provider: ProviderId, val label: String) : OAuthState

    data class Error(val provider: ProviderId, val message: String) : OAuthState
}

/**
 * 账号授权。做法与 QQBot 项目一致（只照协议，不依赖 Cookie）：
 *
 * - Claude：`claude.ai/oauth/authorize` + 本地回调 `127.0.0.1:53692/callback`，
 *   自动收 code 后用 JSON 换 token；端口被占用时才降级为「复制授权码，回 App 自动读剪贴板」。
 * - ChatGPT：优先 **设备码**（`auth.openai.com/api/accounts/deviceauth/usercode` + 轮询），
 *   用户在 Chrome 输入验证码后自动完成；设备码接口不可用时回退浏览器 + `127.0.0.1:1455` 回调。
 * - Gemini：没有 OAuth 额度接口，保持 Cookie。
 *
 * 拿到的 access token 既能查官方额度，也能直接发保活消息（见 data 层）；
 * refresh token 会存进 credentials.extra，由适配器自动续期。
 */
object OAuthFlow {

    // ------------------------------------------------------------------ Claude

    private const val CLAUDE_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    private const val CLAUDE_AUTHORIZE = "https://claude.ai/oauth/authorize"
    private const val CLAUDE_REDIRECT_MANUAL = "https://platform.claude.com/oauth/code/callback"
    private const val CLAUDE_LOOPBACK_PORT = 53692
    private const val CLAUDE_LOOPBACK_REDIRECT = "http://localhost:53692/callback"
    private const val CLAUDE_SCOPES =
        "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"
    private val CLAUDE_TOKEN_URLS = listOf(
        "https://platform.claude.com/v1/oauth/token",
        "https://api.anthropic.com/v1/oauth/token",
        "https://console.anthropic.com/v1/oauth/token"
    )

    // ------------------------------------------------------------------ ChatGPT

    private const val OPENAI_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val OPENAI_AUTHORIZE = "https://auth.openai.com/oauth/authorize"
    private const val OPENAI_TOKEN = "https://auth.openai.com/oauth/token"
    private const val OPENAI_SCOPES =
        "openid profile email offline_access api.connectors.read api.connectors.invoke"
    private const val OPENAI_DEVICE_CODE_URL = "https://auth.openai.com/api/accounts/deviceauth/usercode"
    private const val OPENAI_DEVICE_TOKEN_URL = "https://auth.openai.com/api/accounts/deviceauth/token"
    private const val OPENAI_DEVICE_REDIRECT = "https://auth.openai.com/deviceauth/callback"
    private const val OPENAI_DEVICE_VERIFY_URL = "https://auth.openai.com/codex/device"
    private const val OPENAI_LOOPBACK_PORT = 1455
    private const val OPENAI_LOOPBACK_PORT_FALLBACK = 1457
    private const val OPENAI_ORIGINATOR = "pi"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<OAuthState>(OAuthState.Idle)
    val state: StateFlow<OAuthState> = _state.asStateFlow()

    private val random = SecureRandom()

    @Volatile private var pendingProvider: ProviderId? = null
    @Volatile private var pendingAccountId: String = ""
    @Volatile private var pendingVerifier: String = ""
    @Volatile private var pendingChallenge: String = ""
    @Volatile private var pendingState: String = ""
    @Volatile private var pendingRedirect: String = ""
    @Volatile private var loopback: ServerSocket? = null
    private var flowJob: Job? = null

    val supported: Set<ProviderId> = setOf(ProviderId.CLAUDE, ProviderId.CHATGPT)

    /** 打开 Chrome 开始授权；只支持 Claude / ChatGPT。 */
    fun launch(context: Context, provider: ProviderId, accountId: String = "") {
        if (provider !in supported) {
            _state.value = OAuthState.Error(provider, "该服务没有可用的 OAuth 额度接口，请用 Cookie 连接")
            return
        }
        cancel()
        pendingProvider = provider
        pendingAccountId = accountId
        val pkce = generatePkce()
        pendingVerifier = pkce.verifier
        pendingChallenge = pkce.challenge
        pendingState = randomToken(32)
        when (provider) {
            ProviderId.CLAUDE -> startClaude(context)
            ProviderId.CHATGPT -> startChatGpt(context)
            else -> Unit
        }
    }

    /** Claude：本地回调自动完成；端口被占用时降级为手动复制授权码。 */
    private fun startClaude(context: Context) {
        val port = bindServer(CLAUDE_LOOPBACK_PORT)
        val redirect = if (port != null) CLAUDE_LOOPBACK_REDIRECT else CLAUDE_REDIRECT_MANUAL
        pendingRedirect = redirect
        val authUrl = buildClaudeAuthorizeUrl(pendingChallenge, pendingState, redirect)
        _state.value = OAuthState.Waiting(
            ProviderId.CLAUDE,
            if (port != null) {
                "已用 Chrome 打开授权页。登录并授权后会自动回到 App 完成连接。"
            } else {
                "已用 Chrome 打开授权页。登录后复制页面上的授权码，回到本 App 会自动读取并完成连接。"
            }
        )
        if (port != null) acceptLoop(ProviderId.CLAUDE, checkState = true)
        openInChrome(context, authUrl)
    }

    /** ChatGPT：设备码优先。 */
    private fun startChatGpt(context: Context) {
        _state.value = OAuthState.Waiting(ProviderId.CHATGPT, "正在获取 ChatGPT 设备验证码…")
        flowJob = scope.launch {
            val device = requestDeviceCode()
            if (device == null) {
                startChatGptBrowser(context)
                return@launch
            }
            _state.value = OAuthState.DeviceCode(
                provider = ProviderId.CHATGPT,
                userCode = device.userCode,
                verificationUrl = OPENAI_DEVICE_VERIFY_URL,
                hint = "在 Chrome 打开验证页并输入下面的代码，完成后会自动继续"
            )
            openInChrome(context, OPENAI_DEVICE_VERIFY_URL)
            pollDeviceCode(device)
        }
    }

    private data class DeviceCodeInfo(
        val deviceAuthId: String,
        val userCode: String,
        val intervalSeconds: Long
    )

    private fun requestDeviceCode(): DeviceCodeInfo? {
        val engine = HttpEngine(Store.settingsNow().proxy)
        val body = JSONObject().put("client_id", OPENAI_CLIENT_ID).toString()
        val result = engine.postJson(
            OPENAI_DEVICE_CODE_URL,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "User-Agent" to HttpEngine.DEFAULT_USER_AGENT
            ),
            body = body
        )
        val response = (result as? HttpResult.Ok)?.response ?: return null
        if (!response.ok) return null
        val root = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
        val id = root.optString("device_auth_id").takeIf { it.isNotBlank() } ?: return null
        val code = root.optString("user_code").takeIf { it.isNotBlank() } ?: return null
        val interval = root.optDouble("interval", 5.0).toLong().coerceIn(5L, 30L)
        return DeviceCodeInfo(id, code, interval)
    }

    private suspend fun pollDeviceCode(device: DeviceCodeInfo) {
        val engine = HttpEngine(Store.settingsNow().proxy)
        var interval = device.intervalSeconds
        val deadline = System.currentTimeMillis() + 180_000L
        while (System.currentTimeMillis() < deadline && _state.value is OAuthState.DeviceCode) {
            val body = JSONObject()
                .put("device_auth_id", device.deviceAuthId)
                .put("user_code", device.userCode)
                .toString()
            val result = engine.postJson(
                OPENAI_DEVICE_TOKEN_URL,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "User-Agent" to HttpEngine.DEFAULT_USER_AGENT
                ),
                body = body
            )
            when (result) {
                is HttpResult.Ok -> {
                    val response = result.response
                    if (response.ok) {
                        val root = runCatching { JSONObject(response.body) }.getOrNull()
                        val code = root?.optString("authorization_code").orEmpty()
                        val verifier = root?.optString("code_verifier").orEmpty()
                        if (code.isNotBlank() && verifier.isNotBlank()) {
                            _state.value = OAuthState.Exchanging(ProviderId.CHATGPT)
                            val token = exchangeOpenAi(code, verifier, OPENAI_DEVICE_REDIRECT)
                            if (token.ok) {
                                saveAndVerify(ProviderId.CHATGPT, token)
                            } else {
                                _state.value = OAuthState.Error(ProviderId.CHATGPT, token.message)
                            }
                            return
                        }
                    } else {
                        val errorCode = runCatching {
                            JSONObject(response.body).optJSONObject("error")?.optString("code")
                        }.getOrNull().orEmpty()
                        if (errorCode.contains("slow_down")) {
                            interval = (interval + 5).coerceAtMost(60)
                        } else if (errorCode.contains("pending")) {
                            // 继续轮询
                        } else if (response.code == 429) {
                            interval = (interval + 5).coerceAtMost(60)
                        }
                    }
                }

                is HttpResult.Fail -> Unit
            }
            delay(interval * 1000L)
        }
        if (_state.value is OAuthState.DeviceCode) {
            _state.value = OAuthState.Error(ProviderId.CHATGPT, "设备码登录超时，请重试")
        }
    }

    /** 设备码不可用时，回退浏览器 OAuth（127.0.0.1:1455 / 1457）。 */
    private fun startChatGptBrowser(context: Context) {
        val port = bindOpenAiPort()
        if (port == null) {
            _state.value = OAuthState.Error(
                ProviderId.CHATGPT,
                "设备码不可用，且本地回调端口 1455/1457 都被占用，请关闭占用端口的程序后重试"
            )
            return
        }
        pendingRedirect = "http://127.0.0.1:$port/auth/callback"
        val authUrl = buildOpenAiAuthorizeUrl(pendingChallenge, pendingState, pendingRedirect)
        _state.value = OAuthState.Waiting(
            ProviderId.CHATGPT,
            "已用 Chrome 打开授权页，登录并授权后会自动回到 App。"
        )
        acceptLoop(ProviderId.CHATGPT, checkState = true)
        openInChrome(context, authUrl)
    }

    /**
     * Claude 流程兜底：App 回到前台时调用，自动读剪贴板里的 `code#state` 并兑换 token。
     * 只有本地回调端口被占用（走手动模式）时才会生效。
     */
    fun captureFromClipboard(context: Context): Boolean {
        val provider = pendingProvider ?: return false
        if (provider != ProviderId.CLAUDE) return false
        if (pendingRedirect != CLAUDE_REDIRECT_MANUAL) return false
        if (_state.value !is OAuthState.Waiting) return false
        val raw = readClipboard(context) ?: return false
        val code = parseClaudeCode(raw) ?: return false
        _state.value = OAuthState.Exchanging(provider)
        flowJob = scope.launch {
            val token = exchangeClaude(code)
            if (token.ok) {
                saveAndVerify(ProviderId.CLAUDE, token)
            } else {
                Store.record(
                    provider = ProviderId.CLAUDE,
                    ok = false,
                    text = "Claude 授权登录失败",
                    detail = token.message,
                    kind = com.aicode.quotasentry.core.EventKind.ERROR
                )
                _state.value = OAuthState.Error(ProviderId.CLAUDE, token.message)
            }
        }
        return true
    }

    /** 用户取消 / 关闭面板。 */
    fun cancel() {
        stopLoopback()
        runCatching { flowJob?.cancel() }
        flowJob = null
        pendingProvider = null
        pendingAccountId = ""
        pendingVerifier = ""
        pendingChallenge = ""
        pendingState = ""
        pendingRedirect = ""
        _state.value = OAuthState.Idle
    }

    /** UI 消费掉成功/失败提示后调用。 */
    fun clearResult() {
        if (_state.value is OAuthState.Success || _state.value is OAuthState.Error) {
            _state.value = OAuthState.Idle
            pendingProvider = null
            pendingAccountId = ""
        }
    }

    // ------------------------------------------------------------------ 授权 URL

    fun buildClaudeAuthorizeUrl(challenge: String, state: String, redirectUri: String): String {
        val params = linkedMapOf(
            "code" to "true",
            "client_id" to CLAUDE_CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "scope" to CLAUDE_SCOPES,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state
        )
        return CLAUDE_AUTHORIZE + "?" + encodeQuery(params)
    }

    fun buildOpenAiAuthorizeUrl(challenge: String, state: String, redirectUri: String): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to OPENAI_CLIENT_ID,
            "redirect_uri" to redirectUri,
            "scope" to OPENAI_SCOPES,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to OPENAI_ORIGINATOR
        )
        return OPENAI_AUTHORIZE + "?" + encodeQuery(params)
    }

    private fun encodeQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> queryEncode(k) + "=" + queryEncode(v) }

    private fun queryEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // ------------------------------------------------------------------ 本地回调

    /** 绑定 Claude 的本地回调端口；返回端口或 null。 */
    private fun bindServer(port: Int): Int? {
        stopLoopback()
        val server = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", port))
                soTimeout = 180_000
            }
        }.getOrNull() ?: return null
        loopback = server
        return port
    }

    private fun bindOpenAiPort(): Int? {
        for (port in intArrayOf(OPENAI_LOOPBACK_PORT, OPENAI_LOOPBACK_PORT_FALLBACK)) {
            if (bindServer(port) != null) return port
        }
        return null
    }

    private fun acceptLoop(provider: ProviderId, checkState: Boolean) {
        val server = loopback ?: return
        flowJob = scope.launch {
            try {
                while (_state.value is OAuthState.Waiting) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching {
                        socket.use { client ->
                            client.soTimeout = 10_000
                            val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
                            val requestLine = reader.readLine().orEmpty()
                            val path = requestLine.split(' ').getOrNull(1).orEmpty()
                            val redirectUri = Uri.parse("http://127.0.0.1$path")
                            val code = redirectUri.getQueryParameter("code").orEmpty()
                            val returnedState = redirectUri.getQueryParameter("state").orEmpty()
                            val stateOk = !checkState || returnedState.isBlank() || returnedState == pendingState
                            val ok = code.isNotBlank() && stateOk
                            val html = successHtml(ok)
                            val bytes = html.toByteArray(Charsets.UTF_8)
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            client.getOutputStream().write(header.toByteArray(Charsets.UTF_8))
                            client.getOutputStream().write(bytes)
                            client.getOutputStream().flush()
                            if (ok) {
                                _state.value = OAuthState.Exchanging(provider)
                                stopLoopback()
                                val token = when (provider) {
                                    ProviderId.CLAUDE -> exchangeClaude(code)
                                    else -> exchangeOpenAi(code, pendingVerifier, pendingRedirect)
                                }
                                if (token.ok) {
                                    saveAndVerify(provider, token)
                                } else {
                                    Store.record(
                                        provider = provider,
                                        ok = false,
                                        text = provider.label + " 授权登录失败",
                                        detail = token.message,
                                        kind = com.aicode.quotasentry.core.EventKind.ERROR
                                    )
                                    _state.value = OAuthState.Error(provider, token.message)
                                }
                            }
                        }
                    }
                }
            } finally {
                stopLoopback()
            }
        }
    }

    private fun successHtml(ok: Boolean): String {
        val color = if (ok) "#57E6C0" else "#FF8AA8"
        val title = if (ok) "授权成功" else "授权失败"
        val desc = if (ok) "请返回「额度哨兵」" else "请回到 App 重试"
        return "<html><meta charset='utf-8'><body style='background:#07090F;color:$color;font-family:sans-serif;" +
            "display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>" +
            "<div style='text-align:center'><h2>$title</h2><p>$desc</p></div></body></html>"
    }

    // ------------------------------------------------------------------ 换 token

    private data class TokenResult(
        val ok: Boolean,
        val accessToken: String = "",
        val refreshToken: String = "",
        val expiresIn: Long = 0,
        val accountId: String = "",
        val message: String = ""
    )

    private fun exchangeClaude(codeWithState: String): TokenResult {
        val (code, stateFromCode) = splitClaudeCode(codeWithState)
        val state = stateFromCode.ifBlank { pendingState }
        var lastMessage = "授权码兑换失败"
        val engine = HttpEngine(Store.settingsNow().proxy)
        for (url in CLAUDE_TOKEN_URLS) {
            val body = JSONObject().apply {
                put("grant_type", "authorization_code")
                put("client_id", CLAUDE_CLIENT_ID)
                put("code", code)
                put("state", state)
                put("redirect_uri", pendingRedirect)
                put("code_verifier", pendingVerifier)
            }.toString()
            val result = engine.postJson(
                url,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json"
                ),
                body = body
            )
            when (result) {
                is HttpResult.Ok -> {
                    val response = result.response
                    if (!response.ok) {
                        lastMessage = describeTokenError(response.code, response.body)
                        continue
                    }
                    val token = parseTokenResponse(response.body)
                    if (token.accessToken.isNotBlank()) return token
                    lastMessage = "Token 响应里没有 access_token"
                }

                is HttpResult.Fail -> lastMessage = result.message
            }
        }
        return TokenResult(false, message = lastMessage)
    }

    private fun exchangeOpenAi(code: String, verifier: String, redirectUri: String): TokenResult {
        val engine = HttpEngine(Store.settingsNow().proxy)
        val fields = linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to OPENAI_CLIENT_ID,
            "code" to code,
            "code_verifier" to verifier,
            "redirect_uri" to redirectUri
        )
        return when (val result = engine.postForm(
            OPENAI_TOKEN,
            headers = mapOf("Accept" to "application/json"),
            fields = fields
        )) {
            is HttpResult.Ok -> {
                val response = result.response
                if (!response.ok) {
                    TokenResult(false, message = describeTokenError(response.code, response.body))
                } else {
                    parseTokenResponse(response.body)
                }
            }

            is HttpResult.Fail -> TokenResult(false, message = result.message)
        }
    }

    private fun parseTokenResponse(body: String): TokenResult {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return TokenResult(false, message = "Token 响应不是合法 JSON")
        val access = root.optString("access_token")
        if (access.isBlank()) return TokenResult(false, message = describeTokenError(0, body))
        return TokenResult(
            ok = true,
            accessToken = access,
            refreshToken = root.optString("refresh_token"),
            expiresIn = root.optLong("expires_in", 0L),
            accountId = extractOpenAiAccountId(root.optString("id_token"))
        )
    }

    private fun describeTokenError(code: Int, body: String): String {
        val suffix = runCatching {
            val root = JSONObject(body)
            root.optString("error_description").ifBlank {
                root.optString("error").ifBlank { root.optString("message") }
            }
        }.getOrDefault("")
        return buildString {
            append("授权码兑换失败")
            if (code > 0) append("（HTTP $code）")
            if (suffix.isNotBlank()) append("：").append(suffix.take(160))
        }
    }

    // ------------------------------------------------------------------ 保存与验证

    private fun saveAndVerify(provider: ProviderId, token: TokenResult) {
        val now = System.currentTimeMillis()
        val extra = buildMap {
            put("oauthSource", "oauth")
            if (token.refreshToken.isNotBlank()) put("refreshToken", token.refreshToken)
            if (token.expiresIn > 0) put("oauthExpiresAt", (now + token.expiresIn * 1000L).toString())
            if (token.accountId.isNotBlank()) put("accountId", token.accountId)
        }
        val accountId = pendingAccountId
        if (accountId.isNotBlank()) {
            Store.updateAccountById(accountId) { account ->
                account.copy(
                    credentials = account.credentials.copy(
                        accessToken = token.accessToken,
                        extra = account.credentials.extra + extra
                    ),
                    linked = true,
                    lastError = ""
                )
            }
        } else {
            Store.updateAccount(provider) { account ->
                account.copy(
                    credentials = account.credentials.copy(
                        accessToken = token.accessToken,
                        extra = account.credentials.extra + extra
                    ),
                    linked = true,
                    lastError = ""
                )
            }
        }
        val account = if (accountId.isNotBlank()) {
            Store.accountById(accountId) ?: Store.account(provider)
        } else {
            Store.account(provider)
        }
        _state.value = OAuthState.Exchanging(provider)
        scope.launch {
            when (val result = runCatching {
                Providers.of(provider).connect(account, Store.settingsNow())
            }.getOrNull()) {
                is ConnectResult.Ok -> {
                    Store.updateAccountById(account.id) { current ->
                        current.copy(
                            linked = true,
                            plan = result.plan.ifBlank { current.plan },
                            label = result.label.ifBlank { current.label },
                            lastVerifiedAt = System.currentTimeMillis(),
                            lastError = ""
                        )
                    }
                    val label = result.label.ifBlank {
                        if (provider == ProviderId.CLAUDE) "Claude Code" else "ChatGPT"
                    }
                    _state.value = OAuthState.Success(provider, label)
                }

                is ConnectResult.Fail -> {
                    Store.updateAccountById(account.id) { it.copy(lastError = result.message) }
                    _state.value = OAuthState.Error(provider, result.message)
                }

                null -> _state.value = OAuthState.Success(provider, "")
            }
            pendingProvider = null
            pendingAccountId = ""
        }
    }

    // ------------------------------------------------------------------ Chrome

    private fun openInChrome(context: Context, url: String) {
        val base = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chrome = Intent(base).setPackage("com.android.chrome")
        val canChrome = runCatching {
            context.packageManager.resolveActivity(chrome, 0) != null
        }.getOrDefault(false)
        runCatching {
            context.startActivity(if (canChrome) chrome else base)
        }.onFailure {
            _state.value = OAuthState.Error(
                pendingProvider ?: ProviderId.CLAUDE,
                "无法打开浏览器：${it.message ?: "请检查是否安装了 Chrome"}"
            )
        }
    }

    // ------------------------------------------------------------------ 工具

    private data class Pkce(val verifier: String, val challenge: String)

    private fun generatePkce(): Pkce {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return Pkce(verifier, challenge)
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    /** 把剪贴板文本清洗成授权码；Claude 的格式是 `<code>#<state>`。 */
    fun parseClaudeCode(raw: String?): String? {
        val text = raw?.trim()?.replace(Regex("\\s+"), "") ?: return null
        if (text.length < 16) return null
        if (!text.matches(Regex("[A-Za-z0-9_\\-#.]+"))) return null
        return text
    }

    private fun splitClaudeCode(code: String): Pair<String, String> {
        val index = code.indexOf('#')
        return if (index > 0) code.substring(0, index) to code.substring(index + 1) else code to ""
    }

    private fun extractOpenAiAccountId(idToken: String): String {
        if (idToken.isBlank()) return ""
        return runCatching {
            val payload = idToken.split('.').getOrNull(1) ?: return ""
            val decoded = Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '='))
            val root = JSONObject(String(decoded, Charsets.UTF_8))
            val auth = root.optJSONObject("https://api.openai.com/auth")
                ?: root.optJSONObject("https://api.openai.com/auth/")
            auth?.optString("chatgpt_account_id").orEmpty()
        }.getOrDefault("")
    }

    private fun readClipboard(context: Context): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return runCatching { clip.getItemAt(0).coerceToText(context).toString() }.getOrNull()
    }

    private fun stopLoopback() {
        runCatching { loopback?.close() }
        loopback = null
    }

    /** 供 UI 展示。 */
    fun hintFor(provider: ProviderId): String = when (provider) {
        ProviderId.CLAUDE -> "Chrome 授权后自动回到 App 完成连接"
        ProviderId.CHATGPT -> "设备码登录，无需复制 Cookie"
        ProviderId.GEMINI -> "Gemini 没有 OAuth 额度接口，请粘贴 Cookie"
    }
}
