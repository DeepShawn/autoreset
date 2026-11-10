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

sealed interface OAuthState {
    data object Idle : OAuthState

    /** 已在 Chrome 打开授权页，等待用户完成登录。 */
    data class Waiting(val provider: ProviderId, val hint: String) : OAuthState

    /** 已拿到授权码，正在换 token / 验证账号。 */
    data class Exchanging(val provider: ProviderId) : OAuthState

    data class Success(val provider: ProviderId, val label: String) : OAuthState

    data class Error(val provider: ProviderId, val message: String) : OAuthState
}

/**
 * 用系统 Chrome 完成三家账号的 OAuth 授权，回来后自动把 token 写进账号。
 *
 * 为什么必须走 OAuth：Android 上任何 App 都读不到 Chrome 的 Cookie（沙箱隔离），
 * 所以「Chrome 登录后自动带回网页登录态」不可行；能自动带回来的是 OAuth token。
 * 发消息仍然需要网页 Cookie，这里拿到的是「官方额度」用的令牌。
 *
 * - Claude：授权完成后，页面会显示一段 `code#state`，App 回到前台时自动读剪贴板兑换
 *   （对应你说的「自动粘贴」），token 用于 `oauth/usage` 官方额度。
 * - ChatGPT：使用 Codex CLI 的公开 OAuth client，回调地址是 `http://localhost:1455/auth/callback`，
 *   App 内起一个仅监听 127.0.0.1 的临时服务接收回调，全程不需要手抄。
 * - Gemini：没有可用的个人 OAuth 额度接口，保持 Cookie 导入。
 */
object OAuthFlow {

    private const val CLAUDE_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    private const val CLAUDE_SCOPES =
        "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers"
    private const val CLAUDE_AUTHORIZE = "https://claude.ai/oauth/authorize"
    private const val CLAUDE_REDIRECT = "https://platform.claude.com/oauth/code/callback"
    private val CLAUDE_TOKEN_URLS = listOf(
        "https://console.anthropic.com/v1/oauth/token",
        "https://platform.claude.com/v1/oauth/token"
    )

    private const val OPENAI_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val OPENAI_SCOPES =
        "openid profile email offline_access api.connectors.read api.connectors.invoke"
    private const val OPENAI_AUTHORIZE = "https://auth.openai.com/oauth/authorize"
    private const val OPENAI_TOKEN = "https://auth.openai.com/oauth/token"
    private const val OPENAI_ORIGINATOR = "codex_cli_rs"
    private const val OPENAI_PORT = 1455
    private const val OPENAI_PORT_FALLBACK = 1457

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<OAuthState>(OAuthState.Idle)
    val state: StateFlow<OAuthState> = _state.asStateFlow()

    private val random = SecureRandom()

    @Volatile private var pendingProvider: ProviderId? = null
    @Volatile private var pendingVerifier: String = ""
    @Volatile private var pendingChallenge: String = ""
    @Volatile private var pendingState: String = ""
    @Volatile private var pendingRedirect: String = ""
    @Volatile private var loopback: ServerSocket? = null
    private var flowJob: Job? = null

    val supported: Set<ProviderId> = setOf(ProviderId.CLAUDE, ProviderId.CHATGPT)

    /** 打开 Chrome 开始授权；只支持 Claude / ChatGPT。 */
    fun launch(context: Context, provider: ProviderId) {
        if (provider !in supported) {
            _state.value = OAuthState.Error(provider, "该服务没有可用的 OAuth 额度接口，请用 Cookie 连接")
            return
        }
        cancel()
        val pkce = generatePkce()
        pendingProvider = provider
        pendingVerifier = pkce.verifier
        pendingChallenge = pkce.challenge
        pendingState = randomToken(16)
        val authUrl = when (provider) {
            ProviderId.CLAUDE -> {
                pendingRedirect = CLAUDE_REDIRECT
                buildClaudeAuthorizeUrl(pkce.challenge, pendingState)
            }

            ProviderId.CHATGPT -> {
                // Codex CLI 的 redirect 是 127.0.0.1:1455/1457，必须在授权 URL 里用实际绑定端口，
                // 否则 Chrome 回调会连到没人监听的地址。
                val port = bindLoopback()
                if (port == null) {
                    _state.value = OAuthState.Error(
                        provider,
                        "本地回调端口 1455 / 1457 都被占用，请关闭占用端口的程序后重试"
                    )
                    pendingProvider = null
                    return
                }
                pendingRedirect = "http://127.0.0.1:$port/auth/callback"
                buildOpenAiAuthorizeUrl(pkce.challenge, pendingState, pendingRedirect)
            }

            else -> return
        }
        _state.value = OAuthState.Waiting(
            provider = provider,
            hint = when (provider) {
                ProviderId.CLAUDE ->
                    "已用 Chrome 打开授权页。登录完成后，复制页面显示的授权码，回到本 App 会自动读取并完成连接。"
                else ->
                    "已用 Chrome 打开授权页。登录并授权后会自动回到 App，无需手动复制。"
            }
        )
        if (provider == ProviderId.CHATGPT) {
            acceptLoop(provider)
        }
        openInChrome(context, authUrl)
    }

    /**
     * Claude 流程：App 回到前台时调用，自动读剪贴板里的 `code#state` 并兑换 token。
     * 返回 true 表示识别到授权码并已开始兑换。
     */
    fun captureFromClipboard(context: Context): Boolean {
        val provider = pendingProvider ?: return false
        if (provider != ProviderId.CLAUDE) return false
        if (_state.value !is OAuthState.Waiting) return false
        val raw = readClipboard(context) ?: return false
        val code = parseClaudeCode(raw) ?: return false
        _state.value = OAuthState.Exchanging(provider)
        stopLoopback()
        flowJob = scope.launch { exchangeClaudeAndVerify(code) }
        return true
    }

    /** 用户取消 / 关闭面板。 */
    fun cancel() {
        stopLoopback()
        runCatching { flowJob?.cancel() }
        flowJob = null
        pendingProvider = null
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
        }
    }

    // ------------------------------------------------------------------ 各服务授权 URL

    fun buildClaudeAuthorizeUrl(challenge: String, state: String): String {
        val params = linkedMapOf(
            "code" to "true",
            "client_id" to CLAUDE_CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to CLAUDE_REDIRECT,
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
        params.entries.joinToString("&") { (k, v) ->
            queryEncode(k) + "=" + queryEncode(v)
        }

    private fun queryEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

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

    // ------------------------------------------------------------------ 回调 / 兑换

    /** 绑定本地回调端口：优先 1455，其次 1457（Codex CLI 的 allow-list 端口）。 */
    private fun bindLoopback(): Int? {
        stopLoopback()
        for (port in intArrayOf(OPENAI_PORT, OPENAI_PORT_FALLBACK)) {
            val server = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", port))
                    soTimeout = 180_000
                }
            }.getOrNull() ?: continue
            loopback = server
            return port
        }
        return null
    }

    private fun acceptLoop(provider: ProviderId) {
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
                            val query = redirectUri.getQueryParameter("code").orEmpty()
                            val returnedState = redirectUri.getQueryParameter("state").orEmpty()
                            val ok = query.isNotBlank() &&
                                (returnedState.isBlank() || returnedState == pendingState)
                            val html = if (ok) {
                                "<html><meta charset='utf-8'><body style='background:#07090F;color:#57E6C0;font-family:sans-serif;" +
                                    "display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>" +
                                    "<div style='text-align:center'><h2>授权成功</h2><p>请返回「额度哨兵」</p></div></body></html>"
                            } else {
                                "<html><meta charset='utf-8'><body style='background:#07090F;color:#FF8AA8;font-family:sans-serif;" +
                                    "display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>" +
                                    "<div style='text-align:center'><h2>授权失败</h2><p>请回到 App 重试</p></div></body></html>"
                            }
                            val bytes = html.toByteArray(Charsets.UTF_8)
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            client.getOutputStream().write(header.toByteArray(Charsets.UTF_8))
                            client.getOutputStream().write(bytes)
                            client.getOutputStream().flush()
                            if (ok) {
                                _state.value = OAuthState.Exchanging(provider)
                                stopLoopback()
                                exchangeOpenAiAndVerify(provider, query)
                            }
                        }
                    }
                }
            } finally {
                stopLoopback()
            }
        }
    }

    private fun exchangeOpenAiAndVerify(provider: ProviderId, code: String) {
        flowJob = scope.launch {
            val result = exchangeOpenAi(code)
            if (result.ok) {
                saveAndVerify(provider, result)
            } else {
                _state.value = OAuthState.Error(provider, result.message)
            }
        }
    }

    private fun exchangeClaudeAndVerify(code: String) {
        flowJob = scope.launch {
            val result = exchangeClaude(code)
            if (result.ok) {
                saveAndVerify(ProviderId.CLAUDE, result)
            } else {
                _state.value = OAuthState.Error(ProviderId.CLAUDE, result.message)
            }
        }
    }

    private data class TokenResult(
        val ok: Boolean,
        val accessToken: String = "",
        val refreshToken: String = "",
        val expiresIn: Long = 0,
        val accountId: String = "",
        val message: String = ""
    ) {
        companion object {
            fun ok(access: String, refresh: String, expiresIn: Long, accountId: String = "") =
                TokenResult(true, access, refresh, expiresIn, accountId)

            fun fail(message: String) = TokenResult(false, message = message)
        }
    }

    private fun exchangeClaude(code: String): TokenResult {
        val (codePart, statePart) = splitClaudeCode(code)
        var lastMessage = "授权码兑换失败"
        val engine = HttpEngine(Store.settingsNow().proxy)
        for (url in CLAUDE_TOKEN_URLS) {
            val fields = linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to codePart,
                "redirect_uri" to CLAUDE_REDIRECT,
                "client_id" to CLAUDE_CLIENT_ID,
                "code_verifier" to pendingVerifier
            )
            if (statePart.isNotBlank()) fields["state"] = statePart
            when (val result = engine.postForm(url, fields = fields)) {
                is HttpResult.Ok -> {
                    val response = result.response
                    if (!response.ok) {
                        lastMessage = describeTokenError(response.code, response.body)
                        continue
                    }
                    val parsed = parseTokenResponse(response.body)
                    if (parsed.accessToken.isNotBlank()) return parsed
                    lastMessage = "Token 响应里没有 access_token"
                }

                is HttpResult.Fail -> lastMessage = result.message
            }
        }
        return TokenResult.fail(lastMessage)
    }

    private fun exchangeOpenAi(code: String): TokenResult {
        val engine = HttpEngine(Store.settingsNow().proxy)
        val fields = linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to OPENAI_CLIENT_ID,
            "code" to code,
            "redirect_uri" to pendingRedirect,
            "code_verifier" to pendingVerifier
        )
        return when (val result = engine.postForm(OPENAI_TOKEN, fields = fields)) {
            is HttpResult.Ok -> {
                val response = result.response
                if (!response.ok) {
                    TokenResult.fail(describeTokenError(response.code, response.body))
                } else {
                    parseTokenResponse(response.body)
                }
            }

            is HttpResult.Fail -> TokenResult.fail(result.message)
        }
    }

    private fun parseTokenResponse(body: String): TokenResult {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return TokenResult.fail("Token 响应不是合法 JSON")
        val access = root.optString("access_token")
        if (access.isBlank()) return TokenResult.fail(describeTokenError(0, body))
        val refresh = root.optString("refresh_token")
        val expires = root.optLong("expires_in", 0L)
        val accountId = extractOpenAiAccountId(root.optString("id_token"))
        return TokenResult.ok(access, refresh, expires, accountId)
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

    private suspend fun saveAndVerify(provider: ProviderId, token: TokenResult) {
        val now = System.currentTimeMillis()
        val extra = buildMap {
            put("oauthSource", "chrome")
            if (token.refreshToken.isNotBlank()) put("refreshToken", token.refreshToken)
            if (token.expiresIn > 0) put("oauthExpiresAt", (now + token.expiresIn * 1000L).toString())
            if (token.accountId.isNotBlank()) put("accountId", token.accountId)
        }
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
        val account = Store.account(provider)
        _state.value = OAuthState.Exchanging(provider)
        when (val result = runCatching { Providers.of(provider).connect(account, Store.settingsNow()) }.getOrNull()) {
            is ConnectResult.Ok -> {
                Store.updateAccount(provider) { current ->
                    current.copy(
                        linked = true,
                        plan = result.plan.ifBlank { current.plan },
                        label = result.label.ifBlank { current.label },
                        lastVerifiedAt = System.currentTimeMillis(),
                        lastError = ""
                    )
                }
                val label = result.label.ifBlank { if (provider == ProviderId.CLAUDE) "Claude Code" else "ChatGPT" }
                _state.value = OAuthState.Success(provider, label)
            }

            is ConnectResult.Fail -> {
                Store.updateAccount(provider) { it.copy(lastError = result.message) }
                _state.value = OAuthState.Error(provider, result.message)
            }

            null -> _state.value = OAuthState.Success(provider, "")
        }
        pendingProvider = null
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

    /** 供 UI 展示：Chrome 里需要用户做什么。 */
    fun hintFor(provider: ProviderId): String = when (provider) {
        ProviderId.CLAUDE -> "Chrome 登录后复制授权码，回到 App 会自动完成"
        ProviderId.CHATGPT -> "Chrome 授权后自动回到 App"
        ProviderId.GEMINI -> "Gemini 没有 OAuth 额度接口，请粘贴 Cookie"
    }
}
