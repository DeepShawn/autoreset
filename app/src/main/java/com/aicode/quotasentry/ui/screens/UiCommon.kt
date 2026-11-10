package com.aicode.quotasentry.ui.screens

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aicode.quotasentry.core.Account
import com.aicode.quotasentry.core.ConnectResult
import com.aicode.quotasentry.core.EventKind
import com.aicode.quotasentry.core.Formatters
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.QuotaSource
import com.aicode.quotasentry.core.QuotaState
import com.aicode.quotasentry.core.ScheduleMath
import com.aicode.quotasentry.core.ScheduleRule
import com.aicode.quotasentry.core.SendEvent
import com.aicode.quotasentry.core.Store
import com.aicode.quotasentry.data.Providers
import com.aicode.quotasentry.ui.components.GlassButton
import com.aicode.quotasentry.ui.components.GlassButtonStyle
import com.aicode.quotasentry.ui.components.GlassSheet
import com.aicode.quotasentry.ui.components.MText
import com.aicode.quotasentry.ui.theme.QsDims
import com.aicode.quotasentry.ui.theme.QsTheme
import com.aicode.quotasentry.ui.theme.QsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ────────────────────────────────────────────────────────────────────────────
// 通用小工具（仅 UI 层使用，不碰 core / service / data）
// ────────────────────────────────────────────────────────────────────────────

/** 每 30 秒跳一次的时钟，用于刷新倒计时（契约要求三个页面都走它）。 */
@Composable
fun rememberNow(intervalMillis: Long = 30_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMillis) {
        while (true) {
            delay(intervalMillis)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** 卡片 stagger 入场：fade + 上移 12dp + scale 0.98→1，全部走 spring。 */
@Composable
fun StaggerItem(index: Int, content: @Composable () -> Unit) {
    // rememberSaveable：LazyColumn 项滚出屏幕再回来不会重播入场动画。
    var visible by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(30L + index.coerceIn(0, 5) * 45L)
        visible = true
    }
    val t by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
        label = "stagger"
    )
    Box(
        Modifier.graphicsLayer {
            alpha = t.coerceIn(0f, 1f)
            translationY = (1f - t) * 14.dp.toPx()
            scaleX = 0.98f + 0.02f * t
            scaleY = 0.98f + 0.02f * t
        }
    ) {
        content()
    }
}

/** 轻量 Toast 封装，统一中文提示。 */
object AppToast {
    fun show(context: Context, text: String) {
        runCatching { Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }
}

/** 从任意 Context 找 Activity（Dialog / 系统设置跳转需要）。 */
fun Context.findActivityOrNull(): Activity? {
    var ctx: Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** 账号相关的写操作：连接、保存 Cookie、断开，全部集中在这里，页面只调用。 */
object AccountFlow {

    /** 把 Cookie 写进 Store（从剪贴板粘贴 Cookie 后调用）。 */
    fun saveCookie(provider: ProviderId, cookie: String) {
        Store.updateAccount(provider) { account ->
            account.copy(credentials = account.credentials.copy(cookie = cookie.trim()))
        }
    }

    /**
     * 调用 ProviderClient.connect 验证账号，并把结果写回 Store。
     * 网络/解析异常一律转成中文失败结果，不向上抛。
     */
    suspend fun verify(provider: ProviderId): ConnectResult = withContext(Dispatchers.IO) {
        val account = Store.account(provider)
        val settings = Store.settingsNow()
        val result: ConnectResult = try {
            if (!Providers.has(provider)) {
                ConnectResult.Fail("数据层适配器尚未就绪，请稍后重试")
            } else {
                Providers.of(provider).connect(account, settings)
            }
        } catch (t: Throwable) {
            ConnectResult.Fail(t.message?.takeIf { it.isNotBlank() } ?: "连接失败，请检查网络或代理")
        }
        val now = System.currentTimeMillis()
        when (result) {
            is ConnectResult.Ok -> {
                Store.updateAccount(provider) { current ->
                    current.copy(
                        linked = true,
                        plan = result.plan.ifBlank { current.plan },
                        label = result.label.ifBlank { current.label },
                        linkedAt = if (current.linkedAt > 0L) current.linkedAt else now,
                        lastVerifiedAt = now,
                        lastError = ""
                    )
                }
                val patch = result.quota
                if (patch != null) {
                    Store.updateQuota(provider) { quota -> com.aicode.quotasentry.core.QuotaMath.applyPatch(quota, patch, now) }
                }
                Store.record(
                    provider = provider,
                    ok = true,
                    text = "账号连接成功",
                    detail = result.plan.ifBlank { "已验证凭据" },
                    kind = EventKind.LINK,
                    at = now
                )
            }

            is ConnectResult.Fail -> {
                Store.updateAccount(provider) { current ->
                    current.copy(linked = false, lastError = result.message)
                }
                Store.record(
                    provider = provider,
                    ok = false,
                    text = "账号连接失败",
                    detail = result.message,
                    kind = EventKind.ERROR,
                    at = now
                )
            }
        }
        result
    }

    /** 断开连接：清空凭据，避免留着失效 Cookie。 */
    fun disconnect(provider: ProviderId) {
        Store.setAccount(Account.empty(provider))
        Store.updateQuota(provider) { quota ->
            quota.copy(
                officialUsedFraction = null,
                officialResetAt = null,
                officialDetail = "",
                source = QuotaSource.NONE,
                localSent = 0,
                localWindowStart = 0L
            )
        }
        Store.record(provider, true, "已断开连接", "", EventKind.LINK)
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 计划 / 额度的展示辅助
// ────────────────────────────────────────────────────────────────────────────

/** 用官方重置时刻 + 窗口长度算出规则的下一次触发时间（纯计算，用于展示与保存）。 */
fun computeNextRun(rule: ScheduleRule, quota: QuotaState?, windowMillis: Long, now: Long): Long =
    ScheduleMath.computeNext(rule, now, quota?.officialResetAt, windowMillis)

/** 计划卡片上的一行“下次 …”。 */
fun nextRunText(rule: ScheduleRule, next: Long, now: Long): String =
    ScheduleMath.describeNext(rule, next, now)

/** 已连接账号数量。 */
fun connectedCount(accounts: Map<ProviderId, Account>): Int = accounts.values.count { it.linked }

/** 最早连接时间，用于“已运行 X”的展示。 */
fun runningSince(accounts: Map<ProviderId, Account>): Long =
    accounts.values.filter { it.linked }.mapNotNull { it.linkedAt.takeIf { v -> v > 0L } }.minOrNull() ?: 0L

/** 首页顶部胶囊文案。 */
fun guardianStatusText(running: Boolean, accounts: Map<ProviderId, Account>, now: Long): String {
    if (!running) return "未启动 · 点击开启"
    val since = runningSince(accounts)
    return if (since > 0L && now > since) "守护中 · 已运行 " + Formatters.humanDuration(now - since)
    else "守护中 · 等待连接"
}

/** 账号状态说明。 */
fun accountStatusText(account: Account): String = when {
    !account.linked -> "未连接"
    account.plan.isNotBlank() -> "已连接 · ${account.plan}"
    else -> "已连接"
}

/** 计划最近一次执行结果文案。 */
fun lastResultText(rule: ScheduleRule): String = when {
    rule.lastRunAt <= 0L -> "还没有执行过"
    rule.lastResult.isBlank() -> "上次 " + Formatters.relative(rule.lastRunAt) + " 执行"
    else -> rule.lastResult
}

/** 事件图标。 */
fun eventIcon(event: SendEvent): com.aicode.quotasentry.ui.components.QsIcon = when (event.kind) {
    EventKind.SEND -> com.aicode.quotasentry.ui.components.QsIcon.BOLT
    EventKind.REFRESH -> com.aicode.quotasentry.ui.components.QsIcon.REFRESH
    EventKind.LINK -> com.aicode.quotasentry.ui.components.QsIcon.LINK
    EventKind.ERROR -> com.aicode.quotasentry.ui.components.QsIcon.WARN
}

// ────────────────────────────────────────────────────────────────────────────
// 确认弹窗（没有 material AlertDialog，用玻璃 Sheet 实现）
// ────────────────────────────────────────────────────────────────────────────

/** 玻璃确认 Sheet：危险操作（清空记录、断开连接）统一走这里。 */
@Composable
fun ConfirmSheet(
    open: Boolean,
    title: String,
    message: String,
    confirmText: String = "确认",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val p = QsTheme.palette
    GlassSheet(
        open = open,
        onDismiss = onDismiss,
        title = title,
        subtitle = message
    ) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                style = GlassButtonStyle.SECONDARY
            )
            Spacer(Modifier.width(12.dp))
            GlassButton(
                text = confirmText,
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                style = if (danger) GlassButtonStyle.DANGER else GlassButtonStyle.PRIMARY
            )
        }
        if (danger) {
            Spacer(Modifier.height(10.dp))
            MText(
                text = "该操作不可撤销，请确认。",
                color = p.dim,
                style = QsType.tiny,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 小小的文本按钮，用于设置行右侧的“去设置 / 已开启”。 */
@Composable
fun MiniTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val p = QsTheme.palette
    com.aicode.quotasentry.ui.components.GlassChip(
        text = text,
        modifier = modifier,
        onClick = if (enabled) onClick else null,
        height = 30.dp
    )
}

/** 页面底部留白，避免被悬浮 Tab 栏挡住。 */
@Composable
fun BottomSpacer() {
    Spacer(Modifier.height(QsDims.contentBottom))
}

/** 弹出一个居中的提示性 Sheet（例如“本地窗口已重置”需要可读的反馈）。 */
@Composable
fun InfoSheet(open: Boolean, title: String, message: String, onDismiss: () -> Unit) {
    val p = QsTheme.palette
    GlassSheet(open = open, onDismiss = onDismiss, title = title) {
        Spacer(Modifier.height(10.dp))
        MText(text = message, color = p.dim, style = QsType.label)
        Spacer(Modifier.height(18.dp))
        GlassButton(text = "知道了", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}
