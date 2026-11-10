package com.aicode.quotasentry.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aicode.quotasentry.auth.OAuthFlow
import com.aicode.quotasentry.auth.OAuthState
import com.aicode.quotasentry.core.Account
import com.aicode.quotasentry.core.ConnectResult
import com.aicode.quotasentry.core.Formatters
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.Store
import com.aicode.quotasentry.ui.components.GlassButton
import com.aicode.quotasentry.ui.components.GlassButtonStyle
import com.aicode.quotasentry.ui.components.GlassCard
import com.aicode.quotasentry.ui.components.GlassChip
import com.aicode.quotasentry.ui.components.GlassSheet
import com.aicode.quotasentry.ui.components.GlassTextField
import com.aicode.quotasentry.ui.components.LoadingRing
import com.aicode.quotasentry.ui.components.MText
import com.aicode.quotasentry.ui.components.ProviderLogo
import com.aicode.quotasentry.ui.components.QsIcon
import com.aicode.quotasentry.ui.components.StatusDot
import com.aicode.quotasentry.ui.theme.QsTheme
import com.aicode.quotasentry.ui.theme.QsType
import kotlinx.coroutines.launch

/**
 * 账号连接 BottomSheet：粘贴 Cookie / Chrome 授权登录（OAuth）/ 测试连接 / 断开连接。
 *
 * 数据全部写回 [Store]；验证请求走 data 层的 [com.aicode.quotasentry.data.Providers]。
 */
@Composable
fun AccountSheet(provider: ProviderId?, onDismiss: () -> Unit) {
    val p = QsTheme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by Store.accounts.collectAsState()

    // Sheet 关闭动画期间仍需展示原内容，所以记住最近一次打开的 provider。
    var shown by remember { mutableStateOf(provider) }
    LaunchedEffect(provider) { if (provider != null) shown = provider }

    val current = shown
    var cookie by remember(current) { mutableStateOf(current?.let { Store.account(it).credentials.cookie } ?: "") }
    var accessToken by remember(current) { mutableStateOf(current?.let { Store.account(it).credentials.accessToken } ?: "") }
    var hidden by remember(current) { mutableStateOf(true) }
    var verifying by remember(current) { mutableStateOf(false) }
    var message by remember(current) { mutableStateOf<String?>(null) }
    var messageOk by remember(current) { mutableStateOf(false) }

    val oauthState by OAuthFlow.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Claude 授权：Chrome 里登录并复制授权码后，回到前台自动读剪贴板兑换 token。
    DisposableEffect(lifecycleOwner, current) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val waiting = OAuthFlow.state.value
                if (waiting is OAuthState.Waiting && waiting.provider == ProviderId.CLAUDE) {
                    if (!OAuthFlow.captureFromClipboard(context)) {
                        AppToast.show(context, "还没有读取到授权码，请在 Chrome 复制授权码后返回")
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // OAuth 成功：把自动写入的 token 同步到输入框，提示并清掉一次性结果。
    LaunchedEffect(oauthState) {
        when (val result = oauthState) {
            is OAuthState.Success -> {
                if (current == result.provider) {
                    accessToken = Store.account(result.provider).credentials.accessToken
                    messageOk = true
                    message = "已连接：" + result.label
                }
                AppToast.show(context, "已连接：" + result.label)
                OAuthFlow.clearResult()
            }

            else -> Unit
        }
    }

    fun persist(target: ProviderId) {
        Store.updateAccount(target) { account ->
            account.copy(
                credentials = account.credentials.copy(
                    cookie = cookie.trim(),
                    accessToken = accessToken.trim()
                )
            )
        }
    }

    fun dismiss() {
        shown?.let { persist(it) }
        onDismiss()
    }

    GlassSheet(
        open = provider != null,
        onDismiss = { dismiss() },
        title = "连接账号",
        subtitle = "凭据只保存在本机，仅用于访问你自己的账号"
    ) {
        if (current == null) return@GlassSheet
        val account: Account = accounts[current] ?: Account.empty(current)

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderLogo(provider = current, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MText(text = current.label, color = p.text, style = QsType.title, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        color = if (account.linked) p.ok else p.faint,
                        size = 7.dp,
                        halo = account.linked
                    )
                    Spacer(Modifier.width(6.dp))
                    MText(
                        text = if (account.linked) {
                            accountStatusText(account) + if (account.label.isNotBlank()) " · ${account.label}" else ""
                        } else {
                            "未连接"
                        },
                        color = p.dim,
                        style = QsType.caption,
                        maxLines = 1
                    )
                }
            }
            if (account.linked) {
                GlassChip(text = "断开", onClick = {
                    AccountFlow.disconnect(current)
                    AppToast.show(context, "已断开 ${current.label}")
                    onDismiss()
                }, height = 30.dp)
            }
        }

        if (account.lastVerifiedAt > 0L && account.linked) {
            Spacer(Modifier.height(8.dp))
            MText(
                text = "上次验证：" + Formatters.dateTime(account.lastVerifiedAt),
                color = p.faint,
                style = QsType.tiny
            )
        }

        if (!account.lastError.isNullOrBlank() && !account.linked && account.lastError != message) {
            Spacer(Modifier.height(10.dp))
            GlassCard(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                elevation = 0.dp,
                padding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                glow = p.danger.copy(alpha = 0.10f)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    QsIcon(QsIcon.WARN, size = 16.dp, tint = p.danger)
                    Spacer(Modifier.width(8.dp))
                    MText(text = account.lastError, color = p.danger, style = QsType.caption)
                }
            }
        }

        // 本次操作的反馈
        if (message != null) {
            Spacer(Modifier.height(10.dp))
            val tone = if (messageOk) p.ok else p.danger
            GlassCard(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                elevation = 0.dp,
                padding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                glow = tone.copy(alpha = 0.10f)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    QsIcon(if (messageOk) QsIcon.CHECK else QsIcon.WARN, size = 16.dp, tint = tone)
                    Spacer(Modifier.width(8.dp))
                    MText(text = message.orEmpty(), color = tone, style = QsType.caption)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        MText(text = "登录凭据", color = p.dim, style = QsType.sectionTitle)
        Spacer(Modifier.height(8.dp))
        GlassTextField(
            value = cookie,
            onValueChange = { cookie = it },
            placeholder = requiredCookieHint(current),
            singleLine = false,
            minLines = 4,
            maxLines = 8,
            monospace = true,
            password = hidden,
            trailing = {
                GlassChip(
                    text = if (hidden) "显示" else "隐藏",
                    icon = if (hidden) QsIcon.EYE else QsIcon.EYE_OFF,
                    onClick = { hidden = !hidden },
                    height = 30.dp
                )
            }
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip(
                text = "从剪贴板粘贴",
                icon = QsIcon.PASTE,
                onClick = {
                    val clip = readClipboard(context)
                    if (clip.isNullOrBlank()) {
                        AppToast.show(context, "剪贴板里没有文字")
                    } else {
                        cookie = clip
                        persist(current)
                        AppToast.show(context, "已粘贴 Cookie")
                    }
                },
                height = 34.dp
            )
            // 只有 Claude / ChatGPT 有可用的 OAuth 额度接口；Gemini 仍用 Cookie。
            if (current in OAuthFlow.supported) {
                GlassChip(
                    text = "Chrome 授权登录",
                    icon = QsIcon.GLOBE,
                    onClick = {
                        persist(current)
                        OAuthFlow.launch(context, current)
                    },
                    height = 34.dp
                )
            }
        }

        if (current in OAuthFlow.supported) {
            Spacer(Modifier.height(10.dp))
            OAuthPanel(
                provider = current,
                state = oauthState,
                onLaunch = {
                    persist(current)
                    OAuthFlow.launch(context, current)
                },
                onCancel = { OAuthFlow.cancel() },
                onCapture = {
                    if (!OAuthFlow.captureFromClipboard(context)) {
                        AppToast.show(context, "剪贴板里没有找到授权码，请先在 Chrome 复制")
                    }
                }
            )
        }

        // Claude 用 OAuth 令牌读官方 5h / 7d 用量；ChatGPT 用 accessToken 兜底。
        // Gemini 没有可用的令牌接口，不显示这一栏。
        if (current != ProviderId.GEMINI) {
            Spacer(Modifier.height(12.dp))
            MText(
                text = if (current == ProviderId.CLAUDE) {
                    "Claude Code OAuth 令牌（可选，额度更准）"
                } else {
                    "Access Token（可选，兜底）"
                },
                color = p.dim,
                style = QsType.tiny
            )
            Spacer(Modifier.height(6.dp))
            GlassTextField(
                value = accessToken,
                onValueChange = { accessToken = it },
                placeholder = if (current == ProviderId.CLAUDE) {
                    "sk-ant-oat01-…（~/.claude/.credentials.json 里的 accessToken）"
                } else {
                    "eyJhbGciOi…（浏览器 chatgpt.com/api/auth/session 里的 accessToken）"
                },
                singleLine = true,
                monospace = true,
                password = hidden,
                keyboardType = KeyboardType.Text
            )
            Spacer(Modifier.height(6.dp))
            MText(
                text = if (current == ProviderId.CLAUDE) {
                    "仅用于读取官方 5 小时 / 7 天用量；发送保活消息仍需要上面的 claude.ai Cookie。只有令牌没有 Cookie 时不能发送。"
                } else {
                    "Cookie 失效时可以只填 accessToken 查额度 / 发送消息。"
                },
                color = p.faint,
                style = QsType.tiny
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(
                text = "测试连接",
                icon = QsIcon.CHECK,
                loading = verifying,
                enabled = !verifying,
                onClick = {
                    persist(current)
                    verifying = true
                    message = null
                    scope.launch {
                        val result = AccountFlow.verify(current)
                        verifying = false
                        messageOk = result is ConnectResult.Ok
                        message = when (result) {
                            is ConnectResult.Ok -> buildString {
                                append("连接成功")
                                if (result.plan.isNotBlank()) append(" · ${result.plan}")
                                if (result.label.isNotBlank()) append(" · ${result.label}")
                            }

                            is ConnectResult.Fail -> result.message
                        }
                        AppToast.show(context, if (messageOk) "账号验证成功" else "验证失败，请检查 Cookie")
                    }
                },
                modifier = Modifier.weight(1f)
            )
            GlassButton(
                text = "保存",
                icon = QsIcon.CHECK,
                style = GlassButtonStyle.SECONDARY,
                onClick = {
                    persist(current)
                    AppToast.show(context, "已保存，可点测试连接验证")
                },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))
        MText(text = "需要哪些 Cookie", color = p.dim, style = QsType.sectionTitle)
        Spacer(Modifier.height(8.dp))
        GlassCard(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            elevation = 0.dp,
            padding = androidx.compose.foundation.layout.PaddingValues(14.dp)
        ) {
            cookieHelp(current).forEach { line ->
                MText(
                    text = "· $line",
                    color = p.dim,
                    style = QsType.caption,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        MText(
            text = "提示：这是非官方接口，需要用到你浏览器的登录态；凭据只存在本机，不会上传到任何服务器。",
            color = p.faint,
            style = QsType.tiny
        )
        Spacer(Modifier.height(14.dp))
    }
}

private fun readClipboard(context: Context): String? = runCatching {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = manager?.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    clip.getItemAt(0).coerceToText(context)?.toString()
}.getOrNull()

/** Chrome OAuth 授权面板：等待用户操作 / 兑换中 / 出错重试 / 未开始的说明。 */
@Composable
private fun OAuthPanel(
    provider: ProviderId,
    state: OAuthState,
    onLaunch: () -> Unit,
    onCancel: () -> Unit,
    onCapture: () -> Unit
) {
    val p = QsTheme.palette
    val mine: OAuthState? = when (state) {
        is OAuthState.Waiting -> state.takeIf { it.provider == provider }
        is OAuthState.Exchanging -> state.takeIf { it.provider == provider }
        is OAuthState.Error -> state.takeIf { it.provider == provider }
        else -> null
    }
    GlassCard(
        shape = RoundedCornerShape(16.dp),
        elevation = 0.dp,
        padding = PaddingValues(12.dp),
        glow = when (mine) {
            is OAuthState.Error -> p.danger.copy(alpha = 0.08f)
            is OAuthState.Waiting -> p.accent.copy(alpha = 0.08f)
            else -> Color.Transparent
        }
    ) {
        when (mine) {
            is OAuthState.Waiting -> {
                Row(verticalAlignment = Alignment.Top) {
                    QsIcon(QsIcon.GLOBE, size = 15.dp, tint = p.accentInk)
                    Spacer(Modifier.width(8.dp))
                    MText(
                        text = mine.hint,
                        color = p.text,
                        style = QsType.caption,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (provider == ProviderId.CLAUDE) {
                        GlassChip(
                            text = "已复制授权码？点此读取",
                            icon = QsIcon.PASTE,
                            onClick = onCapture,
                            height = 30.dp
                        )
                    }
                    GlassChip(text = "取消", onClick = onCancel, height = 30.dp)
                }
            }

            is OAuthState.Exchanging -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingRing(ringSize = 16.dp, color = p.accent)
                    Spacer(Modifier.width(9.dp))
                    MText(text = "正在完成授权…", color = p.text, style = QsType.caption)
                }
            }

            is OAuthState.Error -> {
                Row(verticalAlignment = Alignment.Top) {
                    QsIcon(QsIcon.WARN, size = 15.dp, tint = p.danger)
                    Spacer(Modifier.width(8.dp))
                    MText(
                        text = mine.message,
                        color = p.danger,
                        style = QsType.caption,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(
                        text = "重试",
                        icon = QsIcon.REFRESH,
                        onClick = onLaunch,
                        height = 30.dp
                    )
                    GlassChip(text = "取消", onClick = onCancel, height = 30.dp)
                }
            }

            else -> {
                MText(
                    text = if (provider == ProviderId.CLAUDE) {
                        "用 Chrome 登录 Claude 并授权，App 会自动读取授权码换取 OAuth 令牌（官方额度更准）；发消息仍需要 Cookie。"
                    } else {
                        "用 Chrome 登录并授权 ChatGPT，授权完成后会自动回到 App，全程无需手动复制。"
                    },
                    color = p.dim,
                    style = QsType.tiny
                )
                Spacer(Modifier.height(5.dp))
                MText(text = OAuthFlow.hintFor(provider), color = p.faint, style = QsType.tiny)
            }
        }
    }
}

private fun requiredCookieHint(provider: ProviderId): String = when (provider) {
    ProviderId.CLAUDE -> "粘贴完整 Cookie，例如 sessionKey=sk-ant-sid01-…; cf_clearance=…"
    ProviderId.CHATGPT -> "粘贴完整 Cookie，例如 __Secure-next-auth.session-token=…"
    ProviderId.GEMINI -> "粘贴完整 Cookie，例如 __Secure-1PSID=…; __Secure-1PSIDTS=…"
}

private fun cookieHelp(provider: ProviderId): List<String> = when (provider) {
    ProviderId.CLAUDE -> listOf(
        "Chrome 授权登录：在 Chrome 登录 Claude 并授权，复制页面上的授权码，返回 App 会自动粘贴兑换",
        "OAuth 令牌只用于读取官方 5 小时 / 7 天额度，发送保活消息仍然需要 claude.ai Cookie",
        "Cookie 方案：claude.ai 登录后，F12 → Network → 任意请求 → 复制 Request Headers 里的 Cookie",
        "必须包含 sessionKey（sk-ant-sid01-…）；遇到 Cloudflare 风控时把 cf_clearance 一起粘贴"
    )

    ProviderId.CHATGPT -> listOf(
        "Chrome 授权登录：登录并授权后会自动回到 App，回调地址为 localhost:1455，无需手动复制",
        "Cookie 方案：chatgpt.com 登录后复制完整 Cookie，必须包含 __Secure-next-auth.session-token",
        "有 cf_clearance 时一起粘贴，成功率更高",
        "也可以只粘贴 accessToken（/api/auth/session 里的 accessToken）"
    )

    ProviderId.GEMINI -> listOf(
        "Gemini 没有可用的 OAuth 额度接口，请粘贴 Cookie",
        "gemini.google.com 登录后复制 Cookie，必须包含 __Secure-1PSID",
        "建议同时带上 __Secure-1PSIDTS、__Secure-3PSID",
        "额度按本地窗口估算，无法读取官方用量"
    )
}

/** 供设置页 / 首页弹 AccountSheet 时复用的小封装（保持调用点简洁）。 */
@Composable
fun AccountSheetHost(provider: ProviderId?, onDismiss: () -> Unit) {
    AccountSheet(provider = provider, onDismiss = onDismiss)
}
