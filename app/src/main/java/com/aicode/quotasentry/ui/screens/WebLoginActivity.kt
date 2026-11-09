package com.aicode.quotasentry.ui.screens

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aicode.quotasentry.core.ConnectResult
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.Store
import com.aicode.quotasentry.ui.components.AuroraBackground
import com.aicode.quotasentry.ui.components.GlassButton
import com.aicode.quotasentry.ui.components.GlassButtonStyle
import com.aicode.quotasentry.ui.components.GlassCard
import com.aicode.quotasentry.ui.components.GlassIconButton
import com.aicode.quotasentry.ui.components.LoadingRing
import com.aicode.quotasentry.ui.components.MText
import com.aicode.quotasentry.ui.components.QsIcon
import com.aicode.quotasentry.ui.components.ThinProgressBar
import com.aicode.quotasentry.ui.components.glassSurface
import com.aicode.quotasentry.ui.theme.QuotaSentryTheme
import com.aicode.quotasentry.ui.theme.QsTheme
import com.aicode.quotasentry.ui.theme.QsType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 内置网页登录：全屏 WebView + 玻璃标题栏。
 *
 * 登录完成后点「完成」：从 [CookieManager] 读取对应域名的完整 Cookie 写回 [Store]，
 * 然后调用 data 层的 connect() 验证。验证失败时提示改用「粘贴 Cookie」。
 */
class WebLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val provider = ProviderId.fromKey(intent.getStringExtra("provider")) ?: ProviderId.CLAUDE
        setContent {
            val settings by Store.settings.collectAsState()
            QuotaSentryTheme(settings) {
                WebLoginScreen(provider = provider, onClose = { finish() })
            }
        }
    }
}

private fun startUrl(provider: ProviderId): String = when (provider) {
    ProviderId.CLAUDE -> "https://claude.ai/login"
    ProviderId.CHATGPT -> "https://chatgpt.com/auth/login"
    ProviderId.GEMINI -> "https://gemini.google.com/app"
}

/** 允许在 WebView 里继续导航的登录域名白名单（其余链接拦截，避免变成通用浏览器）。 */
private val allowedHosts = listOf(
    "claude.ai", "chatgpt.com", "openai.com",
    "google.com", "googleusercontent.com", "gstatic.com",
    "apple.com", "microsoftonline.com"
)

private fun isAllowedHost(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    return allowedHosts.any { suffix -> host == suffix || host.endsWith(".$suffix") }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebLoginScreen(provider: ProviderId, onClose: () -> Unit) {
    val p = QsTheme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var loading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    val fallbackHint = when (provider) {
        ProviderId.GEMINI ->
            "Google 账号在 WebView 里可能拒绝登录。若一直停在登录页，请在浏览器登录 gemini.google.com 后复制 __Secure-1PSID Cookie，回到「设置 → 账号连接」粘贴。"
        ProviderId.CLAUDE ->
            "登录完成后点右上角「完成」即可读取 Cookie。若出现 Cloudflare 验证，需要在 WebView 里完成人机验证。"
        ProviderId.CHATGPT ->
            "登录完成后点右上角「完成」即可读取 Cookie。若卡在验证页，请在浏览器登录后复制 Cookie 粘贴。"
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { webView?.destroy() } }
    }

    fun collectCookie(): String {
        val manager = CookieManager.getInstance()
        val urls = when (provider) {
            ProviderId.CLAUDE -> listOf("https://claude.ai", "https://claude.ai/api")
            ProviderId.CHATGPT -> listOf("https://chatgpt.com", "https://chat.openai.com")
            ProviderId.GEMINI -> listOf(
                "https://gemini.google.com",
                "https://accounts.google.com",
                "https://www.google.com"
            )
        }
        val merged = LinkedHashMap<String, String>()
        urls.forEach { url ->
            val raw = runCatching { manager.getCookie(url) }.getOrNull()
            raw?.split(';')?.forEach { part ->
                val item = part.trim()
                val eq = item.indexOf('=')
                if (eq > 0) {
                    val name = item.substring(0, eq).trim()
                    if (name.isNotEmpty()) merged[name] = item
                }
            }
        }
        return merged.values.joinToString("; ")
    }

    fun finishLogin() {
        if (verifying) return
        val cookie = collectCookie()
        if (cookie.isBlank()) {
            resultText = "没有读到 Cookie：请先在下方网页里完成登录，再点「完成」。"
            return
        }
        runCatching { CookieManager.getInstance().flush() }
        AccountFlow.saveCookie(provider, cookie)
        verifying = true
        resultText = null
        scope.launch {
            val result = AccountFlow.verify(provider)
            verifying = false
            when (result) {
                is ConnectResult.Ok -> {
                    resultText = "登录成功，账号已连接" + if (result.plan.isNotBlank()) " · ${result.plan}" else ""
                    AppToast.show(context, "登录成功，账号已连接")
                    delay(700)
                    onClose()
                }

                is ConnectResult.Fail -> {
                    resultText = "登录成功但验证失败：${result.message}。请改用「粘贴 Cookie」。"
                    AppToast.show(context, "验证失败，请改用粘贴 Cookie")
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(icon = QsIcon.BACK, onClick = onClose, size = 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    MText(
                        text = "网页登录 · ${provider.label}",
                        color = p.text,
                        style = QsType.cardTitle,
                        maxLines = 1
                    )
                    MText(
                        text = startUrl(provider).removePrefix("https://").substringBefore("/"),
                        color = p.dim,
                        style = QsType.tiny,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(10.dp))
                if (verifying) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingRing(ringSize = 16.dp, color = p.accent)
                        Spacer(Modifier.width(8.dp))
                        MText(text = "验证中", color = p.accent, style = QsType.tiny, maxLines = 1)
                    }
                } else {
                    GlassButton(
                        text = "完成",
                        icon = QsIcon.CHECK,
                        onClick = { finishLogin() },
                        height = 40.dp
                    )
                }
            }

            if (loading && progress < 0.999f) {
                ThinProgressBar(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    height = 3.dp
                )
            } else {
                Spacer(Modifier.height(3.dp))
            }

            if (pageError != null) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    elevation = 0.dp,
                    glow = p.danger.copy(alpha = 0.10f)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        QsIcon(QsIcon.WARN, size = 15.dp, tint = p.danger)
                        Spacer(Modifier.width(7.dp))
                        MText(text = pageError.orEmpty(), color = p.danger, style = QsType.caption)
                    }
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .glassSurface(shape = RoundedCornerShape(26.dp), elevation = 14.dp)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = false
                            settings.displayZoomControls = false
                            settings.setSupportMultipleWindows(false)
                            settings.userAgentString = settings.userAgentString +
                                " QuotaSentry/1.0 (Android)"
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    val host = request.url?.host
                                    return if (isAllowedHost(host)) {
                                        false
                                    } else {
                                        pageError = "已拦截站外链接：$host。登录只允许在 ${provider.label} 的域名内进行。"
                                        true
                                    }
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    super.onPageFinished(view, url)
                                    loading = false
                                    progress = 1f
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError
                                ) {
                                    super.onReceivedError(view, request, error)
                                    if (request.isForMainFrame) {
                                        loading = false
                                        pageError = "页面加载失败：${error.description}。请检查网络或代理设置。"
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress / 100f
                                    loading = newProgress < 100
                                }
                            }
                            loadUrl(startUrl(provider))
                            webView = this
                        }
                    }
                )
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (resultText != null) {
                    MText(
                        text = resultText.orEmpty(),
                        color = if (resultText.orEmpty().contains("成功")) p.ok else p.danger,
                        style = QsType.caption,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                MText(text = fallbackHint, color = p.faint, style = QsType.tiny)
            }
        }

        if (verifying) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(40.dp),
                contentAlignment = Alignment.Center
            ) {
                GlassCard(
                    shape = RoundedCornerShape(22.dp),
                    elevation = 24.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingRing(ringSize = 20.dp, color = p.accent)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            MText(text = "正在验证登录", color = p.text, style = QsType.bodyMedium)
                            MText(
                                text = "用刚拿到的 Cookie 访问 ${provider.label} 验证身份",
                                color = p.dim,
                                style = QsType.tiny
                            )
                        }
                    }
                }
            }
        }
    }
}
