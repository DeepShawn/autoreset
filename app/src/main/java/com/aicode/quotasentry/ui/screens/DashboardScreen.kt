package com.aicode.quotasentry.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import com.aicode.quotasentry.core.Account
import com.aicode.quotasentry.core.EventKind
import com.aicode.quotasentry.core.Formatters
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.QuotaMath
import com.aicode.quotasentry.core.QuotaSource
import com.aicode.quotasentry.core.QuotaState
import com.aicode.quotasentry.core.QuotaView
import com.aicode.quotasentry.core.SendEvent
import com.aicode.quotasentry.core.Settings
import com.aicode.quotasentry.core.Store
import com.aicode.quotasentry.service.ServiceController
import com.aicode.quotasentry.ui.components.EmptyHint
import com.aicode.quotasentry.ui.components.GlassButton
import com.aicode.quotasentry.ui.components.GlassButtonStyle
import com.aicode.quotasentry.ui.components.GlassCard
import com.aicode.quotasentry.ui.components.GlassChip
import com.aicode.quotasentry.ui.components.GlassIconButton
import com.aicode.quotasentry.ui.components.GlassSheet
import com.aicode.quotasentry.ui.components.MText
import com.aicode.quotasentry.ui.components.PillTone
import com.aicode.quotasentry.ui.components.ProviderLogo
import com.aicode.quotasentry.ui.components.RowLabel
import com.aicode.quotasentry.ui.components.QsIcon
import com.aicode.quotasentry.ui.components.QuotaRing
import com.aicode.quotasentry.ui.components.SourceBadge
import com.aicode.quotasentry.ui.components.StatusPill
import com.aicode.quotasentry.ui.components.ThinProgressBar
import com.aicode.quotasentry.ui.theme.QsDims
import com.aicode.quotasentry.ui.theme.QsTheme
import com.aicode.quotasentry.ui.theme.QsType
import com.aicode.quotasentry.ui.theme.quotaBrush

/**
 * 首页：守护状态、概览、三家 Provider 额度卡、最近动态。
 *
 * 所有数据来自 [Store] 的 StateFlow；后台操作只走 [ServiceController]。
 */
@Composable
fun DashboardScreen(
    now: Long,
    onOpenAccount: (ProviderId) -> Unit,
    onOpenEvents: () -> Unit,
    onOpenProvider: (ProviderId) -> Unit
) {
    val p = QsTheme.palette
    val context = LocalContext.current
    val accounts by Store.accounts.collectAsState()
    val quotas by Store.quotas.collectAsState()
    val settings by Store.settings.collectAsState()
    val rules by Store.rules.collectAsState()
    val events by Store.events.collectAsState()
    val running by Store.serviceRunning.collectAsState()

    val windowMillis = settings.windowMillis()
    val nextRun = remember(rules, quotas, windowMillis, now) {
        rules.filter { it.enabled }
            .mapNotNull { rule ->
                val target = computeNextRun(rule, quotas[rule.provider], windowMillis, now)
                if (target > 0L) rule.provider to target else null
            }
            .minByOrNull { it.second }
    }
    val lastSend = events.lastOrNull { it.kind == EventKind.SEND }
    val linked = accounts.values.count { it.linked }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = QsDims.screenPadding,
            end = QsDims.screenPadding,
            top = topInset + 8.dp,
            bottom = QsDims.contentBottom
        )
    ) {
        item(key = "header") {
            StaggerItem(0) {
                DashboardHeader(
                    now = now,
                    running = running,
                    accounts = accounts,
                    onOpenEvents = onOpenEvents,
                    onRefresh = {
                        ServiceController.refreshQuota(context, null)
                        AppToast.show(context, "已请求刷新三家额度")
                    },
                    onToggleGuard = {
                        if (running) {
                            ServiceController.stop(context)
                            Store.updateSettings { it.copy(backgroundEnabled = false) }
                            AppToast.show(context, "已停止后台守护")
                        } else {
                            Store.updateSettings { it.copy(backgroundEnabled = true) }
                            ServiceController.start(context)
                            AppToast.show(context, "已启动后台守护")
                        }
                    }
                )
            }
        }

        item(key = "overview") {
            StaggerItem(1) {
                Spacer(Modifier.height(14.dp))
                if (linked == 0) {
                    OnboardingCard(
                        hasRules = rules.any { it.enabled },
                        onConnect = { onOpenAccount(ProviderId.ordered.firstOrNull { accounts[it]?.linked != true } ?: ProviderId.CLAUDE) }
                    )
                } else {
                    OverviewCard(
                        accounts = accounts,
                        running = running,
                        backgroundEnabled = settings.backgroundEnabled,
                        lastSend = lastSend,
                        nextRun = nextRun,
                        now = now,
                        onStartGuard = {
                            Store.updateSettings { it.copy(backgroundEnabled = true) }
                            ServiceController.start(context)
                            AppToast.show(context, "已启动后台守护")
                        }
                    )
                }
            }
        }

        items(ProviderId.ordered, key = { "provider_" + it.key }) { provider ->
            val index = ProviderId.ordered.indexOf(provider)
            StaggerItem(2 + index) {
                Spacer(Modifier.height(12.dp))
                val account = accounts[provider] ?: Account.empty(provider)
                val view = Store.viewFor(provider, now)
                val quota = quotas[provider] ?: Store.quota(provider)
                ProviderCard(
                    provider = provider,
                    account = account,
                    view = view,
                    quota = quota,
                    running = running,
                    backgroundEnabled = settings.backgroundEnabled,
                    windowMillis = windowMillis,
                    now = now,
                    onClick = {
                        if (account.linked) onOpenProvider(provider) else onOpenAccount(provider)
                    },
                    onConnect = { onOpenAccount(provider) },
                    onSendNow = {
                        ServiceController.sendNow(context, provider)
                        AppToast.show(context, "已请求向 ${provider.label} 发送保活消息")
                    },
                    onRefresh = {
                        ServiceController.refreshQuota(context, provider)
                        AppToast.show(context, "正在刷新 ${provider.label} 额度")
                    },
                    onMore = { onOpenProvider(provider) }
                )
            }
        }

        item(key = "timeline") {
            StaggerItem(6) {
                Spacer(Modifier.height(16.dp))
                TimelineCard(events = events, now = now, onOpenAll = onOpenEvents)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DashboardHeader(
    now: Long,
    running: Boolean,
    accounts: Map<ProviderId, Account>,
    onOpenEvents: () -> Unit,
    onRefresh: () -> Unit,
    onToggleGuard: () -> Unit
) {
    val p = QsTheme.palette
    val linked = accounts.values.count { it.linked }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            MText(text = "额度哨兵", style = QsType.largeTitle, color = p.text, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            MText(
                text = when {
                    !running -> "守护未启动"
                    linked == 0 -> "还没有连接账号"
                    else -> "已连接 $linked 家 · 守护中"
                },
                style = QsType.caption,
                color = p.dim,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassIconButton(icon = QsIcon.HISTORY, onClick = onOpenEvents, size = 42.dp)
            GlassIconButton(icon = QsIcon.REFRESH, onClick = onRefresh, size = 42.dp)
        }
    }
}

@Composable
private fun OverviewCard(
    accounts: Map<ProviderId, Account>,
    running: Boolean,
    backgroundEnabled: Boolean,
    lastSend: SendEvent?,
    nextRun: Pair<ProviderId, Long>?,
    now: Long,
    onStartGuard: () -> Unit
) {
    val p = QsTheme.palette
    GlassCard(glow = if (running) p.accent.copy(alpha = 0.08f) else p.warn.copy(alpha = 0.10f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(if (running) p.ok else p.warn, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            MText(
                text = if (running) "守护中" else "守护未启动",
                color = p.text,
                style = QsType.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (running) {
                MText(
                    text = "后台常驻",
                    color = p.dim,
                    style = QsType.footnote
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderId.ordered.forEach { provider ->
                val account = accounts[provider] ?: Account.empty(provider)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(if (account.linked) p.ok else p.faint, CircleShape)
                        )
                        Spacer(Modifier.width(5.dp))
                        MText(
                            text = provider.shortLabel,
                            color = p.text,
                            style = QsType.tiny,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    MText(
                        text = if (account.linked) (account.plan.ifBlank { "已连接" }) else "未连接",
                        color = if (account.linked) p.dim else p.faint,
                        style = QsType.tiny,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.divider))
        Spacer(Modifier.height(10.dp))
        RowLabel(
            label = "最近一次发送",
            value = lastSend?.let { providerLabel(it.provider) + " · " + Formatters.relative(it.at, now) } ?: "还没有发送"
        )
        Spacer(Modifier.height(12.dp))
        if (nextRun != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                MText(
                    text = Formatters.clock(nextRun.second),
                    color = p.accentInk,
                    style = QsType.display,
                    maxLines = 1
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    MText(
                        text = "下一次续命 · " + providerLabel(nextRun.first),
                        color = p.text,
                        style = QsType.caption,
                        maxLines = 1
                    )
                    MText(
                        text = Formatters.humanDuration(nextRun.second - now) + "后",
                        color = p.dim,
                        style = QsType.footnote,
                        maxLines = 1
                    )
                }
            }
        } else {
            MText(
                text = "还没有计划 · 去「计划」页开一条",
                color = p.dim,
                style = QsType.label,
                maxLines = 1
            )
        }
        if (!running) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassMini(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QsIcon(QsIcon.SHIELD, size = 16.dp, tint = p.warn)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    MText(
                        text = if (!backgroundEnabled) "后台保活已关闭" else "后台服务未运行",
                        color = p.text,
                        style = QsType.caption,
                        maxLines = 1
                    )
                    MText(
                        text = "点击开启，划掉任务栏也能继续守护",
                        color = p.dim,
                        style = QsType.tiny,
                        maxLines = 1
                    )
                }
                GlassChip(text = "开启", onClick = onStartGuard, height = 30.dp)
            }
        }
    }
}

/** 概览卡里的浅色内嵌行（用 drawBehind 画一层很淡的底，不新增模糊层）。 */
@Composable
private fun Modifier.glassMini(): Modifier {
    val p = QsTheme.palette
    val shape = RoundedCornerShape(14.dp)
    return this
        .padding(top = 0.dp)
        .background(p.text.copy(alpha = 0.05f), shape)
        .padding(horizontal = 12.dp, vertical = 10.dp)
}

@Composable
private fun OnboardingCard(hasRules: Boolean, onConnect: () -> Unit) {
    val p = QsTheme.palette
    GlassCard(glow = p.accent.copy(alpha = 0.10f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(p.accent.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                QsIcon(QsIcon.SHIELD, size = 20.dp, tint = p.accentInk)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MText(text = "三步开始守护", color = p.text, style = QsType.cardTitle, maxLines = 1)
                MText(
                    text = "连接账号后，哨兵会按计划替你续上额度窗口",
                    color = p.dim,
                    style = QsType.caption,
                    maxLines = 2
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        StepRow(1, "连接账号", "粘贴 Cookie，或用 Chrome 授权登录", done = false)
        StepRow(2, "设定计划", "跟随额度窗口重置，自动发一条保活消息", done = hasRules)
        StepRow(3, "允许后台", "关闭电池优化，确保划掉任务栏后仍在守护", done = false)
        Spacer(Modifier.height(14.dp))
        GlassButton(
            text = "去连接第一个账号",
            icon = QsIcon.LINK,
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun StepRow(index: Int, title: String, detail: String, done: Boolean) {
    val p = QsTheme.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(22.dp)
                .background(if (done) p.accent else p.text.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                QsIcon(QsIcon.CHECK, size = 12.dp, tint = p.onAccent, strokeFraction = 0.16f)
            } else {
                MText(
                    text = index.toString(),
                    color = p.dim,
                    style = QsType.tiny,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            MText(text = title, color = p.text, style = QsType.bodyMedium, maxLines = 1)
            MText(text = detail, color = p.dim, style = QsType.tiny, maxLines = 2)
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderId,
    account: Account,
    view: QuotaView,
    quota: QuotaState,
    running: Boolean,
    backgroundEnabled: Boolean,
    windowMillis: Long,
    now: Long,
    onClick: () -> Unit,
    onConnect: () -> Unit,
    onSendNow: () -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit
) {
    val p = QsTheme.palette
    val official = view.source == QuotaSource.OFFICIAL
    GlassCard(
        onClick = onClick,
        glow = if (view.low) p.danger.copy(alpha = 0.10f) else p.accent.copy(alpha = 0.07f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderLogo(provider = provider, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MText(text = provider.label, color = p.text, style = QsType.cardTitle, maxLines = 1)
                    if (account.plan.isNotBlank()) {
                        Spacer(Modifier.width(7.dp))
                        GlassChip(text = account.plan, height = 22.dp)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (account.linked) {
                        SourceBadge(sourceLabel = view.sourceLabel, official = official)
                    } else {
                        MText(text = "未连接", color = p.dim, style = QsType.caption, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            if (account.linked) {
                QuotaRing(
                    progress = view.remainingFraction,
                    ringSize = 78.dp,
                    low = view.low,
                    dashed = !official
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MText(
                            text = Formatters.percent(view.remainingFraction),
                            color = p.text,
                            style = QsType.ringValue,
                            maxLines = 1
                        )
                        MText(
                            text = if (official) "官方" else "约 ${view.remainingCount ?: "—"}/${view.totalCount ?: "—"} 条",
                            color = p.dim,
                            style = QsType.ringUnit,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        if (!account.linked) {
            Spacer(Modifier.height(10.dp))
            MText(
                text = "连接后即可查看额度、自动续命",
                color = p.dim,
                style = QsType.caption,
                maxLines = 1
            )
            Spacer(Modifier.height(12.dp))
            GlassButton(
                text = "去连接",
                icon = QsIcon.LINK,
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                height = 46.dp
            )
            return@GlassCard
        }

        Spacer(Modifier.height(12.dp))
        val remainingText = view.remainingCount?.let { "还剩约 $it 条" }
            ?: ("剩余 " + Formatters.percent(view.remainingFraction))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            MText(
                text = remainingText,
                color = if (view.low) p.danger else p.text,
                style = QsType.label,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            MText(
                text = view.resetAt?.let { at ->
                    if (at > now) Formatters.countdown(at - now) + "重置" else "即将重置"
                } ?: "窗口未开始",
                color = p.dim,
                style = QsType.footnote,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(9.dp))
        val windowElapsed = view.resetAt?.let { at ->
            (1f - ((at - now).toFloat() / view.windowMillis).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        } ?: 0f
        ThinProgressBar(
            progress = windowElapsed,
            brush = p.accentBrush(),
            height = 6.dp
        )
        Spacer(Modifier.height(8.dp))
        val sent = if (!official) QuotaMath.effectiveSent(quota, now, windowMillis) else quota.localSent
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MText(
                text = "本窗口已发 $sent 条",
                color = p.dim,
                style = QsType.tiny,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            MText(
                text = if (quota.lastSendAt > 0L) "上次发送 " + Formatters.relative(quota.lastSendAt, now) else "还没发送过",
                color = if (quota.lastSendAt > 0L && !quota.lastSendOk) p.danger else p.faint,
                style = QsType.tiny,
                maxLines = 1
            )
        }
        if (view.detail.isNotBlank() && official) {
            Spacer(Modifier.height(4.dp))
            MText(text = view.detail, color = p.faint, style = QsType.tiny, maxLines = 2)
        }
        if (account.lastError.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Top) {
                QsIcon(QsIcon.WARN, size = 13.dp, tint = p.danger)
                Spacer(Modifier.width(5.dp))
                MText(text = account.lastError, color = p.danger, style = QsType.tiny, maxLines = 2)
            }
        }
        Spacer(Modifier.height(13.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(
                text = "续命",
                icon = QsIcon.BOLT,
                onClick = onSendNow,
                modifier = Modifier.weight(1f),
                style = if (view.low) GlassButtonStyle.PRIMARY else GlassButtonStyle.SECONDARY,
                height = 42.dp
            )
            GlassIconButton(icon = QsIcon.MORE, onClick = onMore, size = 42.dp)
        }
    }
}

private fun quotaSummary(view: QuotaView, now: Long): String {
    val countPart = if (view.remainingCount != null) {
        "还剩约 ${view.remainingCount} 条"
    } else {
        "剩余 " + Formatters.percent(view.remainingFraction)
    }
    val resetPart = view.resetAt?.let { at ->
        if (at > now) Formatters.countdown(at - now) + "重置" else "即将重置"
    } ?: "重置时间未知"
    return countPart + " · " + resetPart
}

private fun providerLabel(provider: ProviderId): String = provider.shortLabel


@Composable
private fun TimelineCard(events: List<SendEvent>, now: Long, onOpenAll: () -> Unit) {
    val p = QsTheme.palette
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MText(text = "最近动态", color = p.text, style = QsType.cardTitle, modifier = Modifier.weight(1f))
            GlassChip(text = "全部", icon = QsIcon.CHEVRON_RIGHT, onClick = onOpenAll, height = 30.dp)
        }
        Spacer(Modifier.height(8.dp))
        val recent = events.takeLast(5).asReversed()
        if (recent.isEmpty()) {
            EmptyHint(icon = QsIcon.HISTORY, text = "还没有发送记录\n连上账号后，这里会记录每一次续命")
        } else {
            recent.forEachIndexed { index, event ->
                EventRow(event = event, now = now)
                if (index != recent.lastIndex) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.divider))
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

/** 单条事件行（时间线 / 历史 Sheet 共用）。 */
@Composable
fun EventRow(event: SendEvent, now: Long, compact: Boolean = false) {
    val p = QsTheme.palette
    val tint = if (event.ok) p.accentInk else p.danger
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(if (compact) 30.dp else 32.dp)
                .background(tint.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            QsIcon(icon = eventIcon(event), size = 15.dp, tint = tint)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            MText(
                text = event.text + " · " + event.provider.shortLabel,
                color = p.text,
                style = QsType.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (event.detail.isNotBlank()) {
                MText(
                    text = event.detail,
                    color = if (event.ok) p.dim else p.danger,
                    style = QsType.tiny,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        MText(text = Formatters.relative(event.at, now), color = p.faint, style = QsType.tiny, maxLines = 1)
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 历史记录 BottomSheet
// ────────────────────────────────────────────────────────────────────────────

/** 历史记录：最近 200 条发送 / 刷新 / 连接事件。 */
@Composable
fun EventsSheet(open: Boolean, onDismiss: () -> Unit) {
    val p = QsTheme.palette
    val events by Store.events.collectAsState()
    val now = rememberNow()
    GlassSheet(
        open = open,
        onDismiss = onDismiss,
        title = "历史记录",
        subtitle = "最近 ${events.size} 条发送与刷新记录"
    ) {
        Spacer(Modifier.height(6.dp))
        if (events.isEmpty()) {
            EmptyHint(
                icon = QsIcon.HISTORY,
                text = "还没有记录\n连上账号并执行一次续命后，这里会出现时间线"
            )
        } else {
            Column(Modifier.fillMaxWidth()) {
                events.takeLast(80).asReversed().forEachIndexed { index, event ->
                    EventRow(event = event, now = now, compact = true)
                    if (index != events.takeLast(80).lastIndex) {
                        Spacer(Modifier.height(9.dp))
                        Box(Modifier.fillMaxWidth().padding(start = 42.dp).height(0.5.dp).background(p.divider))
                        Spacer(Modifier.height(9.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Provider 详情 BottomSheet
// ────────────────────────────────────────────────────────────────────────────

/** 单个 Provider 的额度明细 + 手动重置入口。 */
@Composable
fun ProviderDetailSheet(
    provider: ProviderId?,
    onDismiss: () -> Unit,
    onOpenAccount: (ProviderId) -> Unit,
    onOpenEvents: () -> Unit
) {
    val p = QsTheme.palette
    val context = LocalContext.current
    val accounts by Store.accounts.collectAsState()
    val quotas by Store.quotas.collectAsState()
    val events by Store.events.collectAsState()
    val settings by Store.settings.collectAsState()
    val running by Store.serviceRunning.collectAsState()
    val now = rememberNow()

    var shown by remember { mutableStateOf(provider) }
    LaunchedEffect(provider) { if (provider != null) shown = provider }
    val current = shown

    GlassSheet(
        open = provider != null,
        onDismiss = onDismiss,
        title = current?.let { "${it.label} · 额度详情" } ?: "额度详情",
        subtitle = current?.let { provider -> if (accounts[provider]?.linked == true) "官方数据优先，缺失时用本地窗口估算" else "账号未连接" } ?: ""
    ) {
        if (current == null) return@GlassSheet
        val account = accounts[current] ?: Account.empty(current)
        val quota = quotas[current] ?: Store.quota(current)
        val view = Store.viewFor(current, now)
        val official = view.source == QuotaSource.OFFICIAL
        val lastEvent = events.lastOrNull { it.provider == current }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderLogo(provider = current, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MText(text = current.label, color = p.text, style = QsType.title, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(sourceLabel = view.sourceLabel, official = official)
                    Spacer(Modifier.width(6.dp))
                    if (!running) {
                        GlassChip(text = "服务未运行", height = 22.dp)
                    }
                }
            }
            if (account.linked) {
                QuotaRing(progress = view.remainingFraction, ringSize = 72.dp, low = view.low) {
                    if (view.remainingCount != null) {
                        MText(text = view.remainingCount.toString(), color = p.text, style = QsType.ringValue)
                        MText(text = "条", color = p.dim, style = QsType.ringUnit)
                    } else {
                        MText(text = Formatters.percent(view.remainingFraction), color = p.text, style = QsType.ringValue)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        GlassCard(
            shape = RoundedCornerShape(20.dp),
            elevation = 0.dp,
            padding = PaddingValues(14.dp)
        ) {
            RowLabel(label = "剩余额度", value = quotaSummary(view, now))
            Spacer(Modifier.height(8.dp))
            RowLabel(label = "数据来源", value = view.sourceLabel)
            Spacer(Modifier.height(8.dp))
            RowLabel(label = "窗口长度", value = Formatters.humanDuration(view.windowMillis))
            Spacer(Modifier.height(8.dp))
            RowLabel(
                label = "重置时间",
                value = view.resetAt?.let { if (it > now) Formatters.dateTime(it) else "即将重置" } ?: "未知"
            )
            Spacer(Modifier.height(8.dp))
            RowLabel(
                label = "本窗口已发",
                value = (if (!official) QuotaMath.effectiveSent(quota, now, settings.windowMillis()) else quota.localSent).toString() + " 条"
            )
            Spacer(Modifier.height(8.dp))
            RowLabel(
                label = "最后发送",
                value = if (quota.lastSendAt > 0L) Formatters.dateTime(quota.lastSendAt) else "还没有发送"
            )
            if (view.detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                RowLabel(label = "官方明细", value = view.detail)
            }
            if (account.label.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                RowLabel(label = "账号", value = account.label)
            }
            if (lastEvent != null) {
                Spacer(Modifier.height(8.dp))
                RowLabel(
                    label = "最近事件",
                    value = lastEvent.text + " · " + Formatters.relative(lastEvent.at, now)
                )
            }
        }

        if (account.lastError.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                QsIcon(QsIcon.WARN, size = 15.dp, tint = p.danger)
                Spacer(Modifier.width(6.dp))
                MText(text = account.lastError, color = p.danger, style = QsType.caption, maxLines = 3)
            }
        }

        if (!account.linked) {
            Spacer(Modifier.height(14.dp))
            GlassButton(
                text = "去连接账号",
                icon = QsIcon.LINK,
                onClick = { onOpenAccount(current) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
        } else {
            Spacer(Modifier.height(16.dp))
            GlassButton(
                text = "立即续命",
                icon = QsIcon.BOLT,
                onClick = {
                    ServiceController.sendNow(context, current)
                    AppToast.show(context, "已请求发送")
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(
                    text = "刷新额度",
                    icon = QsIcon.REFRESH,
                    style = GlassButtonStyle.SECONDARY,
                    onClick = {
                        ServiceController.refreshQuota(context, current)
                        AppToast.show(context, "正在刷新额度")
                    },
                    modifier = Modifier.weight(1f)
                )
                GlassButton(
                    text = "重置额度窗口",
                    icon = QsIcon.RESET,
                    style = GlassButtonStyle.DANGER,
                    onClick = {
                        // 手动重置：清空本地窗口计数与官方快照，立刻重新开始估算。
                        Store.updateQuota(current) { q ->
                            q.copy(
                                localSent = 0,
                                localWindowStart = System.currentTimeMillis(),
                                officialUsedFraction = null,
                                officialResetAt = null,
                                officialDetail = "",
                                source = QuotaSource.ESTIMATED,
                                fetchedAt = 0L
                            )
                        }
                        AppToast.show(context, "本地窗口已重置")
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(
                    text = "编辑 Cookie",
                    icon = QsIcon.CODE,
                    style = GlassButtonStyle.SECONDARY,
                    onClick = { onOpenAccount(current) },
                    modifier = Modifier.weight(1f),
                    height = 44.dp
                )
                GlassButton(
                    text = "历史记录",
                    icon = QsIcon.HISTORY,
                    style = GlassButtonStyle.SECONDARY,
                    onClick = { onOpenEvents() },
                    modifier = Modifier.weight(1f),
                    height = 44.dp
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        MText(
            text = "说明：官方额度来自网页版内部接口；接口不可用时「额度哨兵」按你设置的窗口与条数上限做本地滚动估算。",
            color = p.faint,
            style = QsType.tiny
        )
        Spacer(Modifier.height(12.dp))
    }
}
