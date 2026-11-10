package com.aicode.quotasentry.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.quotasentry.core.Formatters
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.ScheduleMath
import com.aicode.quotasentry.core.ScheduleMode
import com.aicode.quotasentry.core.ScheduleRule
import com.aicode.quotasentry.core.Store
import com.aicode.quotasentry.ui.components.EmptyHint
import com.aicode.quotasentry.ui.components.GlassButton
import com.aicode.quotasentry.ui.components.GlassButtonStyle
import com.aicode.quotasentry.ui.components.GlassCard
import com.aicode.quotasentry.ui.components.GlassChip
import com.aicode.quotasentry.ui.components.GlassSheet
import com.aicode.quotasentry.ui.components.GlassTextField
import com.aicode.quotasentry.ui.components.MText
import com.aicode.quotasentry.ui.components.MiuixSlider
import com.aicode.quotasentry.ui.components.MiuixSwitch
import com.aicode.quotasentry.ui.components.ProviderLogo
import com.aicode.quotasentry.ui.components.QsIcon
import com.aicode.quotasentry.ui.components.QsTopBar
import com.aicode.quotasentry.ui.components.RowLabel
import com.aicode.quotasentry.ui.components.SegmentedControl
import com.aicode.quotasentry.ui.components.StatusDot
import com.aicode.quotasentry.ui.components.Stepper
import com.aicode.quotasentry.ui.theme.QsDims
import com.aicode.quotasentry.ui.theme.QsTheme
import com.aicode.quotasentry.ui.theme.QsType
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 计划页：每个 Provider 一张计划卡（开关 / 模式 / 下次触发 / 上次结果），
 * 以及新增 / 编辑计划的 BottomSheet。
 */
@Composable
fun SchedulesScreen(now: Long) {
    val p = QsTheme.palette
    val context = LocalContext.current
    val rules by Store.rules.collectAsState()
    val accounts by Store.accounts.collectAsState()
    val quotas by Store.quotas.collectAsState()
    val settings by Store.settings.collectAsState()
    val windowMillis = settings.windowMillis()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduleRule?>(null) }
    var session by remember { mutableIntStateOf(0) }

    fun openEditor(rule: ScheduleRule?) {
        editing = rule
        session += 1
        editorOpen = true
    }

    val sorted = remember(rules) {
        rules.sortedBy { ProviderId.ordered.indexOf(it.provider) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = QsDims.screenPadding,
            end = QsDims.screenPadding,
            top = topInset + 8.dp,
            bottom = QsDims.contentBottom
        )
    ) {
        item(key = "schedule_header") {
            StaggerItem(0) {
                QsTopBar(
                    title = "计划",
                    subtitle = if (sorted.isEmpty()) "还没有计划 · 让额度窗口自动续上" else "共 ${sorted.size} 个计划 · 到点自动发送保活消息"
                )
            }
        }

        if (sorted.isEmpty()) {
            item(key = "schedule_empty") {
                StaggerItem(1) {
                    Spacer(Modifier.height(16.dp))
                    NoPlanCard(
                        onCreateDefaults = {
                            var created = 0
                            ProviderId.ordered.forEach { provider ->
                                if (Store.rulesFor(provider).isEmpty()) {
                                    val base = ScheduleRule(
                                        id = UUID.randomUUID().toString(),
                                        provider = provider,
                                        mode = ScheduleMode.FOLLOW_RESET
                                    )
                                    val next = computeNextRun(base, firstAccountQuota(provider, accounts, quotas), windowMillis, now)
                                    Store.upsertRule(base.copy(nextRunAt = next))
                                    created += 1
                                }
                            }
                            AppToast.show(
                                context,
                                if (created > 0) "已创建 $created 个默认计划（跟随额度窗口）" else "已有计划，无需重复创建"
                            )
                        },
                        onCreateOne = { openEditor(null) }
                    )
                }
            }
        } else {
            items(sorted, key = { "rule_" + it.id }) { rule ->
                val index = sorted.indexOf(rule)
                StaggerItem(1 + index) {
                    Spacer(Modifier.height(12.dp))
                    val providerAccounts = accounts.filter { it.provider == rule.provider }
                    val linkedCount = providerAccounts.count { it.linked }
                    val quota = firstAccountQuota(rule.provider, accounts, quotas)
                    val next = computeNextRun(rule, quota, windowMillis, now)
                    RuleCard(
                        rule = rule,
                        nextAt = next,
                        now = now,
                        accountCount = providerAccounts.size,
                        linkedCount = linkedCount,
                        onToggle = { checked ->
                            Store.updateRule(rule.id) { current ->
                                val updated = current.copy(enabled = checked)
                                val target = if (checked) computeNextRun(updated, firstAccountQuota(current.provider, accounts, quotas), windowMillis, now) else 0L
                                updated.copy(
                                    nextRunAt = if (checked) target.coerceAtLeast(now + 30_000L) else 0L
                                )
                            }
                            AppToast.show(context, if (checked) "已开启 ${rule.provider.label} 计划" else "已暂停 ${rule.provider.label} 计划")
                        },
                        onEdit = { openEditor(rule) }
                    )
                }
            }

            item(key = "schedule_add") {
                StaggerItem(2 + sorted.size) {
                    Spacer(Modifier.height(16.dp))
                    GlassButton(
                        text = "新增计划",
                        icon = QsIcon.PLUS,
                        onClick = { openEditor(null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item(key = "schedule_tip") {
            Spacer(Modifier.height(18.dp))
            MText(
                text = "· 跟随额度窗口：窗口重置后自动发一条，把 5 小时周期续上\n" +
                    "· 固定间隔：每隔 N 分钟发一条\n" +
                    "· 每天定时：每天固定时间发一条\n" +
                    "· 发送与刷新都会记录在首页的最近动态里",
                color = p.faint,
                style = QsType.tiny
            )
            Spacer(Modifier.height(10.dp))
        }
    }

    ScheduleEditorSheet(
        open = editorOpen,
        initial = editing,
        session = session,
        now = now,
        onDismiss = { editorOpen = false }
    )
}

@Composable
private fun NoPlanCard(onCreateDefaults: () -> Unit, onCreateOne: () -> Unit) {
    val p = QsTheme.palette
    GlassCard(glow = p.accent.copy(alpha = 0.09f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(44.dp)
                    .height(44.dp)
                    .background(p.accent.copy(alpha = 0.13f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                QsIcon(QsIcon.CALENDAR, size = 22.dp, tint = p.accentInk)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MText(text = "还没有计划", color = p.text, style = QsType.cardTitle, maxLines = 1)
                MText(
                    text = "计划决定哨兵什么时候替你发保活消息",
                    color = p.dim,
                    style = QsType.caption,
                    maxLines = 2
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassButton(
            text = "一键创建默认计划",
            icon = QsIcon.BOLT,
            onClick = onCreateDefaults,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(9.dp))
        GlassButton(
            text = "手动新增一个计划",
            icon = QsIcon.PLUS,
            style = GlassButtonStyle.SECONDARY,
            onClick = onCreateOne,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RuleCard(
    rule: ScheduleRule,
    nextAt: Long,
    now: Long,
    accountCount: Int,
    linkedCount: Int,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit
) {
    val linked = linkedCount > 0
    val p = QsTheme.palette
    GlassCard(onClick = onEdit, glow = if (rule.enabled) p.accent.copy(alpha = 0.06f) else p.text.copy(alpha = 0.02f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderLogo(provider = rule.provider, size = 38.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MText(text = rule.provider.label, color = p.text, style = QsType.cardTitle, maxLines = 1)
                    Spacer(Modifier.width(7.dp))
                    GlassChip(text = ScheduleMath.describe(rule), height = 22.dp)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        color = when {
                            !rule.enabled -> p.faint
                            linkedCount == 0 -> p.warn
                            linkedCount < accountCount -> p.warn
                            else -> p.ok
                        },
                        size = 7.dp,
                        halo = rule.enabled
                    )
                    Spacer(Modifier.width(6.dp))
                    MText(
                        text = when {
                            !rule.enabled -> "已暂停"
                            linkedCount == 0 -> "该服务没有已连接账号"
                            linkedCount < accountCount -> "部分账号未连接（$linkedCount/$accountCount）"
                            else -> ScheduleMath.describeNext(rule, nextAt, now)
                        },
                        color = when {
                            !rule.enabled -> p.dim
                            linkedCount < accountCount -> p.warn
                            else -> p.dim
                        },
                        style = QsType.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            MiuixSwitch(checked = rule.enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.divider))
        Spacer(Modifier.height(10.dp))
        RowLabel(
            label = "发送对象",
            value = "将发送给 $accountCount 个账号 · 已连接 $linkedCount",
            valueColor = if (linkedCount < accountCount) p.warn else p.text
        )
        Spacer(Modifier.height(7.dp))
        RowLabel(
            label = "保活消息",
            value = rule.message.ifBlank { "默认 ${com.aicode.quotasentry.core.DefaultMessages.forProvider(rule.provider)}" }
        )
        Spacer(Modifier.height(7.dp))
        RowLabel(
            label = "上次执行",
            value = lastResultText(rule),
            valueColor = if (rule.lastRunAt > 0L && rule.lastResult.contains("失败")) p.danger else p.text
        )
        Spacer(Modifier.height(7.dp))
        RowLabel(
            label = "同步额度",
            value = if (rule.syncQuota) "发送后刷新官方额度" else "不刷新"
        )
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassChip(text = "编辑", icon = QsIcon.CODE, onClick = onEdit, height = 32.dp)
            GlassChip(
                text = if (rule.syncQuota) "同步额度已开" else "同步额度已关",
                icon = QsIcon.REFRESH,
                height = 32.dp
            )
            Spacer(Modifier.weight(1f))
            MText(
                text = if (rule.nextRunAt > 0L) "排程 " + Formatters.clock(rule.nextRunAt) else "未排程",
                color = p.faint,
                style = QsType.tiny,
                maxLines = 1
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 计划编辑 Sheet
// ────────────────────────────────────────────────────────────────────────────

private fun shortModeLabel(mode: ScheduleMode): String = when (mode) {
    ScheduleMode.FOLLOW_RESET -> "跟随窗口"
    ScheduleMode.INTERVAL -> "固定间隔"
    ScheduleMode.DAILY -> "每天定时"
}

@Composable
private fun ScheduleEditorSheet(
    open: Boolean,
    initial: ScheduleRule?,
    session: Int,
    now: Long,
    onDismiss: () -> Unit
) {
    val p = QsTheme.palette
    val context = LocalContext.current
    val accounts by Store.accounts.collectAsState()
    val quotas by Store.quotas.collectAsState()
    val settings by Store.settings.collectAsState()
    val windowMillis = settings.windowMillis()

    var provider by remember(session) { mutableStateOf(initial?.provider ?: ProviderId.CLAUDE) }
    var mode by remember(session) { mutableStateOf(initial?.mode ?: ScheduleMode.FOLLOW_RESET) }
    var interval by remember(session) { mutableIntStateOf(initial?.intervalMinutes ?: 270) }
    var hour by remember(session) { mutableIntStateOf(initial?.atHour ?: 9) }
    var minute by remember(session) { mutableIntStateOf(initial?.atMinute ?: 0) }
    var message by remember(session) { mutableStateOf(initial?.message ?: "") }
    var syncQuota by remember(session) { mutableStateOf(initial?.syncQuota ?: true) }

    val draft = remember(initial, session, provider, mode, interval, hour, minute, message, syncQuota) {
        val base = initial ?: ScheduleRule(id = UUID.randomUUID().toString(), provider = provider)
        base.copy(
            provider = provider,
            mode = mode,
            intervalMinutes = interval,
            atHour = hour,
            atMinute = minute,
            message = message.trim(),
            syncQuota = syncQuota,
            enabled = initial?.enabled ?: true
        )
    }
    val nextPreview = computeNextRun(draft, firstAccountQuota(provider, accounts, quotas), windowMillis, now)

    GlassSheet(
        open = open,
        onDismiss = onDismiss,
        title = if (initial == null) "新增计划" else "编辑计划",
        subtitle = if (initial == null) "为某个 Provider 设定自动发送时间" else ScheduleMath.describe(initial)
    ) {
        Spacer(Modifier.height(8.dp))

        // Provider
        MText(text = "服务", color = p.dim, style = QsType.sectionTitle)
        Spacer(Modifier.height(8.dp))
        if (initial == null) {
            SegmentedControl(
                options = ProviderId.ordered,
                selected = provider,
                onSelect = { provider = it },
                label = { it.shortLabel }
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderLogo(provider = provider, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                MText(text = provider.label, color = p.text, style = QsType.bodyMedium, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                MText(text = "（编辑时不可切换服务）", color = p.faint, style = QsType.tiny, maxLines = 1)
            }
        }

        Spacer(Modifier.height(16.dp))
        MText(text = "触发方式", color = p.dim, style = QsType.sectionTitle)
        Spacer(Modifier.height(8.dp))
        SegmentedControl(
            options = ScheduleMode.entries,
            selected = mode,
            onSelect = { mode = it },
            label = { shortModeLabel(it) }
        )
        Spacer(Modifier.height(8.dp))
        MText(text = mode.hint, color = p.faint, style = QsType.tiny)

        when (mode) {
            ScheduleMode.INTERVAL -> {
                Spacer(Modifier.height(16.dp))
                RowLabel(
                    label = "发送间隔",
                    value = "$interval 分钟（${Formatters.humanDuration(interval * 60_000L)}）"
                )
                Spacer(Modifier.height(6.dp))
                MiuixSlider(
                    value = interval.toFloat(),
                    valueRange = 30f..720f,
                    onValueChange = { raw ->
                        val rounded = ((raw / 5f).roundToInt() * 5).coerceIn(30, 720)
                        interval = rounded
                    }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MText(text = "30 分钟", color = p.faint, style = QsType.tiny)
                    MText(text = "12 小时", color = p.faint, style = QsType.tiny)
                }
            }

            ScheduleMode.DAILY -> {
                Spacer(Modifier.height(16.dp))
                RowLabel(label = "发送时间", value = String.format("%02d:%02d", hour, minute))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MText(text = "时", color = p.dim, style = QsType.caption)
                    Spacer(Modifier.width(8.dp))
                    Stepper(
                        value = hour,
                        onValueChange = { hour = it },
                        range = 0..23,
                        format = { String.format("%02d 时", it) }
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MText(text = "分", color = p.dim, style = QsType.caption)
                    Spacer(Modifier.width(8.dp))
                    Stepper(
                        value = minute,
                        onValueChange = { minute = it },
                        range = 0..55,
                        step = 5,
                        format = { String.format("%02d 分", it) }
                    )
                }
            }

            ScheduleMode.FOLLOW_RESET -> {
                Spacer(Modifier.height(16.dp))
                MText(
                    text = "哨兵会在额度窗口重置后自动发一条消息，把下一个 5 小时窗口续上；没有官方重置时间时按上次发送时间 + 窗口长度推算。",
                    color = p.faint,
                    style = QsType.tiny
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        MText(text = "保活消息", color = p.dim, style = QsType.sectionTitle)
        Spacer(Modifier.height(8.dp))
        GlassTextField(
            value = message,
            onValueChange = { message = it },
            placeholder = "留空使用默认消息「${com.aicode.quotasentry.core.DefaultMessages.forProvider(provider)}」",
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Hi", "ping", "在吗").forEach { word ->
                GlassChip(
                    text = word,
                    selected = message == word,
                    onClick = { message = if (message == word) "" else word },
                    height = 32.dp
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        GlassCard(
            shape = RoundedCornerShape(18.dp),
            elevation = 0.dp,
            padding = PaddingValues(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MText(text = "发送后同步官方额度", color = p.text, style = QsType.bodyMedium, maxLines = 1)
                    MText(
                        text = "发完消息顺手刷新一次额度数据",
                        color = p.dim,
                        style = QsType.tiny,
                        maxLines = 1
                    )
                }
                MiuixSwitch(checked = syncQuota, onCheckedChange = { syncQuota = it })
            }
        }

        Spacer(Modifier.height(14.dp))
        RowLabel(
            label = "下次触发",
            value = if (nextPreview > 0L) {
                Formatters.clock(nextPreview) + "（" + Formatters.humanDuration(nextPreview - now) + "后）"
            } else {
                "等待生效"
            },
            valueColor = p.accentInk
        )
        Spacer(Modifier.height(18.dp))
        GlassButton(
            text = if (initial == null) "创建计划" else "保存修改",
            icon = QsIcon.CHECK,
            onClick = {
                val saved = draft.copy(
                    nextRunAt = if (draft.enabled) {
                        computeNextRun(draft, firstAccountQuota(provider, accounts, quotas), windowMillis, now)
                            .coerceAtLeast(now + 30_000L)
                    } else {
                        0L
                    }
                )
                Store.upsertRule(saved)
                AppToast.show(context, if (initial == null) "计划已创建" else "计划已保存")
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (initial != null) {
            Spacer(Modifier.height(10.dp))
            GlassButton(
                text = "删除这个计划",
                icon = QsIcon.TRASH,
                style = GlassButtonStyle.DANGER,
                onClick = {
                    Store.deleteRule(initial.id)
                    AppToast.show(context, "计划已删除")
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                height = 46.dp
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
