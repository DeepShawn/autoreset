package com.aicode.quotasentry.ui.screens

import android.Manifest
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aicode.quotasentry.core.AccentColor
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.ProxyType
import com.aicode.quotasentry.core.Store
import com.aicode.quotasentry.core.ThemeMode
import com.aicode.quotasentry.service.BackgroundStatus
import com.aicode.quotasentry.service.ServiceController
import com.aicode.quotasentry.update.UpdateCenter
import com.aicode.quotasentry.update.UpdateParser
import com.aicode.quotasentry.update.UpdateState
import com.aicode.quotasentry.ui.components.GlassButton
import com.aicode.quotasentry.ui.components.GlassButtonStyle
import com.aicode.quotasentry.ui.components.GlassChip
import com.aicode.quotasentry.ui.components.GlassSheet
import com.aicode.quotasentry.ui.components.GlassTextField
import com.aicode.quotasentry.ui.components.ListRow
import com.aicode.quotasentry.ui.components.LoadingRing
import com.aicode.quotasentry.ui.components.MText
import com.aicode.quotasentry.ui.components.MiuixSlider
import com.aicode.quotasentry.ui.components.MiuixSwitch
import com.aicode.quotasentry.ui.components.ProviderLogo
import com.aicode.quotasentry.ui.components.QsIcon
import com.aicode.quotasentry.ui.components.QsTopBar
import com.aicode.quotasentry.ui.components.RowLabel
import com.aicode.quotasentry.ui.components.SectionCard
import com.aicode.quotasentry.ui.components.SegmentedControl
import com.aicode.quotasentry.ui.components.StatusDot
import com.aicode.quotasentry.ui.components.Stepper
import com.aicode.quotasentry.ui.components.ThinProgressBar
import com.aicode.quotasentry.ui.theme.QsDims
import com.aicode.quotasentry.ui.theme.QsTheme
import com.aicode.quotasentry.ui.theme.QsType
import com.aicode.quotasentry.ui.theme.accentColorOf
import kotlin.math.roundToInt

/**
 * 设置页：账号连接 / 额度与刷新 / 后台保活 / 网络 / 外观 / 维护与关于。
 *
 * 所有改动立即写 [Store]，后台服务下一次 tick 生效；开关类操作直接走 [ServiceController]。
 */
@Composable
fun SettingsScreen(now: Long, onOpenAccount: (ProviderId) -> Unit) {
    val p = QsTheme.palette
    val context = LocalContext.current
    val accounts by Store.accounts.collectAsState()
    val settings by Store.settings.collectAsState()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    var status by remember { mutableStateOf(safeStatus(context)) }
    LaunchedEffect(now, settings.backgroundEnabled) { status = safeStatus(context) }

    var showMiuiGuide by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // 自动更新：订阅进程内单例状态，手动检查后的「已是最新」用 toast 反馈。
    val updateState by UpdateCenter.state.collectAsState()
    var manualCheck by remember { mutableStateOf(false) }
    LaunchedEffect(updateState, manualCheck) {
        if (!manualCheck) return@LaunchedEffect
        when (val s = updateState) {
            is UpdateState.UpToDate -> {
                AppToast.show(context, "已是最新版本 v" + s.version)
                manualCheck = false
                UpdateCenter.dismiss()
            }

            is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Error -> manualCheck = false
            else -> Unit
        }
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
        item(key = "settings_header") {
            StaggerItem(0) {
                QsTopBar(
                    title = "设置",
                    subtitle = "账号、额度、后台与外观都在这里"
                )
            }
        }

        item(key = "settings_accounts") {
            StaggerItem(1) {
                Spacer(Modifier.height(16.dp))
                SectionCard(title = "账号连接") {
                    ProviderId.ordered.forEachIndexed { index, provider ->
                        val account = accounts[provider]
                        val linked = account?.linked == true
                        ListRow(
                            title = provider.label,
                            subtitle = when {
                                !linked -> "未连接 · 粘贴 Cookie 或 Chrome 授权登录"
                                account.plan.isNotBlank() -> "已连接 · ${account.plan}" +
                                    if (account.label.isNotBlank()) " · ${account.label}" else ""
                                else -> "已连接"
                            },
                            leading = {
                                Box(
                                    Modifier
                                        .size(QsDims.leadingIcon)
                                        .clip(CircleShape)
                                        .background(p.text.copy(alpha = 0.06f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    ProviderLogo(provider = provider, size = 24.dp)
                                }
                            },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(
                                        color = if (linked) p.ok else p.faint,
                                        size = 7.dp,
                                        halo = linked
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    MText(
                                        text = if (linked) "已连接" else "去连接",
                                        color = if (linked) p.dim else p.accent,
                                        style = QsType.tiny,
                                        maxLines = 1
                                    )
                                }
                            },
                            showChevron = true,
                            divider = index != ProviderId.ordered.lastIndex,
                            onClick = { onOpenAccount(provider) }
                        )
                    }
                }
            }
        }

        item(key = "settings_quota") {
            StaggerItem(2) {
                Spacer(Modifier.height(16.dp))
                SectionCard(title = "额度与刷新") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            MText(text = "额度窗口长度", color = p.text, style = QsType.bodyMedium, maxLines = 1)
                            MText(
                                text = "默认 5 小时，与官方重置周期一致",
                                color = p.dim,
                                style = QsType.tiny,
                                maxLines = 1
                            )
                        }
                        Stepper(
                            value = settings.quotaWindowHours,
                            range = 1..12,
                            onValueChange = { hours -> Store.updateSettings { it.copy(quotaWindowHours = hours) } },
                            format = { "$it 小时" }
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(p.divider))
                    Spacer(Modifier.height(12.dp))
                    LimitSliderRow(
                        label = "Claude 条数上限",
                        value = settings.claudeLimit,
                        range = 10f..200f,
                        onCommit = { limit -> Store.updateSettings { it.copy(claudeLimit = limit) } }
                    )
                    LimitSliderRow(
                        label = "ChatGPT 条数上限",
                        value = settings.chatgptLimit,
                        range = 10f..300f,
                        onCommit = { limit -> Store.updateSettings { it.copy(chatgptLimit = limit) } }
                    )
                    LimitSliderRow(
                        label = "Gemini 条数上限",
                        value = settings.geminiLimit,
                        range = 10f..300f,
                        onCommit = { limit -> Store.updateSettings { it.copy(geminiLimit = limit) } }
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.5.dp).background(p.divider))
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        RowLabel(
                            label = "自动刷新官方额度",
                            value = if (settings.autoRefreshMinutes <= 0) "只手动刷新" else "每 ${settings.autoRefreshMinutes} 分钟"
                        )
                        Spacer(Modifier.height(8.dp))
                        SegmentedControl(
                            options = listOf(0, 5, 15, 30, 60),
                            selected = settings.autoRefreshMinutes,
                            onSelect = { minutes -> Store.updateSettings { it.copy(autoRefreshMinutes = minutes) } },
                            label = { if (it <= 0) "关" else "$it 分" }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        item(key = "settings_background") {
            StaggerItem(3) {
                Spacer(Modifier.height(16.dp))
                SectionCard(title = "后台保活") {
                    ListRow(
                        title = "后台守护",
                        subtitle = "前台服务常驻，划掉任务栏也不会停止",
                        leadingIcon = QsIcon.SHIELD,
                        trailing = {
                            MiuixSwitch(
                                checked = settings.backgroundEnabled,
                                onCheckedChange = { checked ->
                                    Store.updateSettings { it.copy(backgroundEnabled = checked) }
                                    if (checked) {
                                        ServiceController.start(context)
                                        AppToast.show(context, "已开启后台守护")
                                    } else {
                                        ServiceController.stop(context)
                                        AppToast.show(context, "已关闭后台守护")
                                    }
                                    status = safeStatus(context)
                                }
                            )
                        },
                        divider = true
                    )
                    StatusRow(
                        title = "前台服务",
                        ok = status.serviceRunning,
                        okText = "运行中",
                        badText = "未运行",
                        actionText = if (settings.backgroundEnabled) null else "去开启",
                        onAction = {
                            Store.updateSettings { it.copy(backgroundEnabled = true) }
                            ServiceController.start(context)
                            status = safeStatus(context)
                        }
                    )
                    StatusRow(
                        title = "电池优化白名单",
                        ok = status.batteryUnrestricted,
                        okText = "已加入白名单",
                        badText = "可能被系统限制",
                        actionText = if (status.batteryUnrestricted) null else "去设置",
                        onAction = {
                            val activity = context.findActivityOrNull()
                            val requested = activity?.let { ServiceController.requestBatteryExemption(it) } ?: false
                            if (!requested) ServiceController.openAppDetails(context)
                        }
                    )
                    StatusRow(
                        title = "精确闹钟权限",
                        ok = status.exactAlarms,
                        okText = "已允许",
                        badText = "仅能使用非精确唤醒",
                        actionText = if (status.exactAlarms) null else "去设置",
                        onAction = {
                            val activity = context.findActivityOrNull()
                            if (activity != null) ServiceController.openExactAlarmSettings(activity)
                        }
                    )
                    StatusRow(
                        title = "通知权限",
                        ok = status.notifications,
                        okText = "已开启",
                        badText = "无法显示常驻通知",
                        actionText = if (status.notifications) null else "去设置",
                        onAction = {
                            val activity = context.findActivityOrNull()
                            if (!status.notifications && activity != null && Build.VERSION.SDK_INT >= 33) {
                                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
                            } else {
                                ServiceController.openNotificationSettings(context)
                            }
                        },
                        divider = false
                    )
                    Spacer(Modifier.height(2.dp))
                    ListRow(
                        title = "MIUI / HyperOS 自启动",
                        subtitle = "小米机型需要手动允许自启动并锁定后台",
                        leadingIcon = QsIcon.AUTO_START,
                        showChevron = true,
                        onClick = { showMiuiGuide = true }
                    )
                    ListRow(
                        title = "常驻通知",
                        subtitle = "通知栏常驻显示守护状态",
                        leadingIcon = QsIcon.BELL,
                        trailing = {
                            MiuixSwitch(
                                checked = settings.ongoingNotification,
                                onCheckedChange = { checked ->
                                    Store.updateSettings { it.copy(ongoingNotification = checked) }
                                }
                            )
                        }
                    )
                    ListRow(
                        title = "发送结果提醒",
                        subtitle = "发送失败时提醒我（连续失败才提醒一次）",
                        leadingIcon = QsIcon.SEND,
                        trailing = {
                            MiuixSwitch(
                                checked = settings.resultNotification,
                                onCheckedChange = { checked ->
                                    Store.updateSettings { it.copy(resultNotification = checked) }
                                }
                            )
                        },
                        divider = false
                    )
                }
            }
        }

        item(key = "settings_network") {
            StaggerItem(4) {
                Spacer(Modifier.height(16.dp))
                NetworkSection()
            }
        }

        item(key = "settings_appearance") {
            StaggerItem(5) {
                Spacer(Modifier.height(16.dp))
                AppearanceSection()
            }
        }

        item(key = "settings_about") {
            StaggerItem(6) {
                Spacer(Modifier.height(16.dp))
                SectionCard(title = "维护与关于") {
                    ListRow(
                        title = "清空发送记录",
                        subtitle = "删除本机保存的全部发送 / 刷新事件",
                        leadingIcon = QsIcon.TRASH,
                        showChevron = true,
                        onClick = { showClearConfirm = true }
                    )
                    ListRow(
                        title = "免责声明与使用说明",
                        subtitle = "非官方接口、仅个人自用、账号风险提示",
                        leadingIcon = QsIcon.INFO,
                        showChevron = true,
                        onClick = { showDisclaimer = true }
                    )
                    val updateSubtitle = when (val s = updateState) {
                        UpdateState.Idle -> "当前版本 v${UpdateCenter.currentVersion}"
                        UpdateState.Checking -> "正在检查新版本…"
                        is UpdateState.UpToDate -> "已是最新 v${s.version}"
                        is UpdateState.Available ->
                            "发现新版本 v${s.info.versionName} · " + UpdateParser.humanSize(s.info.sizeBytes)
                        is UpdateState.Downloading ->
                            "正在下载 v${s.info.versionName} · " + (s.progress.coerceAtLeast(0f) * 100).roundToInt() + "%"
                        is UpdateState.Error -> s.message
                    }
                    val checkUpdate: (() -> Unit)? = when (updateState) {
                        UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Available -> null
                        else -> {
                            ({ manualCheck = true; UpdateCenter.check(context) })
                        }
                    }
                    ListRow(
                        title = "检查更新",
                        subtitle = updateSubtitle,
                        leadingIcon = QsIcon.DOWNLOAD,
                        trailing = {
                            when (val s = updateState) {
                                UpdateState.Checking -> LoadingRing(ringSize = 18.dp, color = p.accent)
                                is UpdateState.Available -> GlassChip(text = "可更新", height = 26.dp)
                                is UpdateState.Downloading -> MText(
                                    text = (s.progress.coerceAtLeast(0f) * 100).roundToInt().toString() + "%",
                                    color = p.accent,
                                    style = QsType.tiny,
                                    maxLines = 1
                                )
                                is UpdateState.Error -> GlassChip(
                                    text = "重试",
                                    onClick = { manualCheck = true; UpdateCenter.check(context) },
                                    height = 26.dp
                                )
                                else -> MText(
                                    text = "v" + UpdateCenter.currentVersion,
                                    color = p.dim,
                                    style = QsType.caption,
                                    maxLines = 1
                                )
                            }
                        },
                        onClick = checkUpdate
                    )
                    if (updateState is UpdateState.Downloading) {
                        val s = updateState as UpdateState.Downloading
                        ThinProgressBar(
                            progress = s.progress.coerceAtLeast(0f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            height = 4.dp
                        )
                    }
                    ListRow(
                        title = "版本",
                        subtitle = "额度哨兵 QuotaSentry",
                        leadingIcon = QsIcon.CODE,
                        trailing = {
                            MText(
                                text = UpdateCenter.currentVersion,
                                color = p.dim,
                                style = QsType.caption,
                                maxLines = 1
                            )
                        },
                        divider = false
                    )
                }
                Spacer(Modifier.height(10.dp))
                MText(
                    text = "本应用只是把你自己的账号会话包装成一个更省心的提醒器，不提供任何账号，也不外传你的 Cookie。",
                    color = p.faint,
                    style = QsType.tiny,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    // ── 弹窗 ────────────────────────────────────────────────────────────────
    ConfirmSheet(
        open = showClearConfirm,
        title = "清空发送记录",
        message = "将删除本机保存的全部发送 / 刷新事件，用于时间线和通知的历史会一并消失。",
        confirmText = "确认清空",
        danger = true,
        onConfirm = {
            Store.clearEvents()
            AppToast.show(context, "发送记录已清空")
        },
        onDismiss = { showClearConfirm = false }
    )

    GlassSheet(
        open = showMiuiGuide,
        onDismiss = { showMiuiGuide = false },
        title = "MIUI / HyperOS 保活设置",
        subtitle = "小米 / 红米机型请按下面 4 步设置，否则后台可能被清理"
    ) {
        Spacer(Modifier.height(8.dp))
        MiuiStep(1, "允许自启动", "设置 → 应用设置 → 应用管理 → 额度哨兵 → 自启动，允许自启动")
        MiuiStep(2, "省电策略设为无限制", "应用信息 → 省电策略 → 无限制")
        MiuiStep(3, "锁定后台", "最近任务里下拉额度哨兵卡片 → 点击锁图标，避免一键清理时被杀")
        MiuiStep(4, "允许通知", "应用通知设置里打开「后台保活」和「发送与额度提醒」两个渠道")
        Spacer(Modifier.height(14.dp))
        GlassButton(
            text = "去自启动管理页",
            icon = QsIcon.AUTO_START,
            onClick = {
                if (!ServiceController.openAutoStartSettings(context)) {
                    ServiceController.openAppDetails(context)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        GlassButton(
            text = "打开应用详情页",
            icon = QsIcon.SETTINGS,
            style = GlassButtonStyle.SECONDARY,
            onClick = { ServiceController.openAppDetails(context) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
    }

    GlassSheet(
        open = showDisclaimer,
        onDismiss = { showDisclaimer = false },
        title = "免责声明",
        subtitle = "请在使用前阅读"
    ) {
        Spacer(Modifier.height(8.dp))
        DisclaimerBlock(
            title = "非官方接口",
            body = "本应用通过三家服务网页版的内部接口访问你本人的账号（Cookie / access token），这些接口不是公开 API，随时可能变更或失效。"
        )
        DisclaimerBlock(
            title = "仅个人自用",
            body = "所有凭据只保存在本机，不会上传到任何服务器；请不要把本应用或你的 Cookie 分享给他人。"
        )
        DisclaimerBlock(
            title = "账号风险",
            body = "自动化发送消息可能触发服务方的风控或使用条款限制。请自行评估风险，建议只用自己的个人账号、低频使用。"
        )
        DisclaimerBlock(
            title = "额度估算",
            body = "官方额度接口不可用时，剩余条数与重置时间是按你设置的窗口和条数上限做的本地估算，仅供参考。"
        )
        DisclaimerBlock(
            title = "后台限制",
            body = "Android 厂商的省电策略可能杀掉后台服务；请在系统设置里允许自启动、关闭电池优化，并保持常驻通知。"
        )
        Spacer(Modifier.height(14.dp))
        GlassButton(
            text = "我知道了",
            onClick = { showDisclaimer = false },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
    }
}

private fun safeStatus(context: android.content.Context): BackgroundStatus =
    runCatching { ServiceController.status(context) }.getOrDefault(BackgroundStatus())

@Composable
private fun StatusRow(
    title: String,
    ok: Boolean,
    okText: String,
    badText: String,
    actionText: String?,
    onAction: () -> Unit,
    divider: Boolean = true
) {
    val p = QsTheme.palette
    ListRow(
        title = title,
        subtitle = if (ok) okText else badText,
        leadingIcon = if (ok) QsIcon.CHECK else QsIcon.WARN,
        trailing = {
            if (actionText != null) {
                GlassChip(text = actionText, onClick = onAction, height = 30.dp)
            } else {
                MText(text = "已开启", color = p.ok, style = QsType.tiny, maxLines = 1)
            }
        },
        divider = divider
    )
}

@Composable
private fun MiuiStep(index: Int, title: String, detail: String) {
    val p = QsTheme.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(22.dp)
                .background(p.accent.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            MText(text = index.toString(), color = p.accent, style = QsType.tiny)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            MText(text = title, color = p.text, style = QsType.bodyMedium, maxLines = 1)
            MText(text = detail, color = p.dim, style = QsType.tiny)
        }
    }
}

@Composable
private fun DisclaimerBlock(title: String, body: String) {
    val p = QsTheme.palette
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        MText(text = title, color = p.text, style = QsType.bodyMedium)
        Spacer(Modifier.height(3.dp))
        MText(text = body, color = p.dim, style = QsType.caption)
    }
}

/** 条数上限滑块行：拖动时只改本地状态，松手才写 Store，避免频繁落盘。 */
@Composable
private fun LimitSliderRow(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Int) -> Unit
) {
    var local by remember(value) { mutableIntStateOf(value.coerceIn(range.start.toInt(), range.endInclusive.toInt())) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        RowLabel(label = label, value = "${local} 条")
        Spacer(Modifier.height(2.dp))
        MiuixSlider(
            value = local.toFloat(),
            valueRange = range,
            onValueChange = { raw -> local = raw.roundToInt().coerceIn(range.start.toInt(), range.endInclusive.toInt()) },
            onValueChangeFinished = { onCommit(local) }
        )
        Spacer(Modifier.height(2.dp))
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 网络
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun NetworkSection() {
    val p = QsTheme.palette
    val context = LocalContext.current
    val settings by Store.settings.collectAsState()
    val proxy = settings.proxy
    var host by remember(proxy.host) { mutableStateOf(proxy.host) }
    var port by remember(proxy.port) { mutableStateOf(proxy.port.toString()) }
    var username by remember(proxy.username) { mutableStateOf(proxy.username) }
    var password by remember(proxy.password) { mutableStateOf(proxy.password) }
    var hidden by remember { mutableStateOf(true) }

    fun save() {
        val portValue = port.toIntOrNull()?.coerceIn(1, 65535) ?: 7890
        Store.updateSettings { current ->
            current.copy(
                proxy = current.proxy.copy(
                    host = host.trim(),
                    port = portValue,
                    username = username.trim(),
                    password = password
                )
            )
        }
        AppToast.show(context, "代理配置已保存")
    }

    SectionCard(title = "网络") {
        ListRow(
            title = "使用代理",
            subtitle = "国内直连 claude.ai / chatgpt.com 通常必须",
            leadingIcon = QsIcon.GLOBE,
            trailing = {
                MiuixSwitch(
                    checked = proxy.enabled,
                    onCheckedChange = { checked ->
                        Store.updateSettings { it.copy(proxy = it.proxy.copy(enabled = checked)) }
                    }
                )
            },
            divider = proxy.enabled
        )
        if (proxy.enabled) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                MText(text = "代理类型", color = p.dim, style = QsType.tiny)
                Spacer(Modifier.height(6.dp))
                SegmentedControl(
                    options = ProxyType.entries,
                    selected = proxy.type,
                    onSelect = { type -> Store.updateSettings { it.copy(proxy = it.proxy.copy(type = type)) } },
                    label = { it.label }
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassTextField(
                        value = host,
                        onValueChange = { host = it },
                        placeholder = "代理主机，例如 127.0.0.1",
                        singleLine = true,
                        modifier = Modifier.weight(1.6f),
                        keyboardType = KeyboardType.Uri
                    )
                    GlassTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                        placeholder = "端口",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number
                    )
                }
                Spacer(Modifier.height(10.dp))
                GlassTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = "用户名（可选）",
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                GlassTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "密码（可选）",
                    singleLine = true,
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
                Spacer(Modifier.height(12.dp))
                GlassButton(
                    text = "保存代理配置",
                    icon = QsIcon.CHECK,
                    onClick = { save() },
                    modifier = Modifier.fillMaxWidth(),
                    height = 46.dp
                )
            }
        }
        ListRow(
            title = "仅 Wi-Fi 下发送",
            subtitle = "省流量，但移动网络下不会自动续命",
            leadingIcon = QsIcon.WIFI,
            trailing = {
                MiuixSwitch(
                    checked = settings.wifiOnly,
                    onCheckedChange = { checked ->
                        Store.updateSettings { it.copy(wifiOnly = checked) }
                    }
                )
            },
            divider = false
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 外观
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun AppearanceSection() {
    val p = QsTheme.palette
    val settings by Store.settings.collectAsState()
    var glass by remember(settings.glassIntensity) { mutableStateOf(settings.glassIntensity) }

    SectionCard(title = "外观") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            MText(text = "主题", color = p.text, style = QsType.bodyMedium)
            Spacer(Modifier.height(8.dp))
            SegmentedControl(
                options = ThemeMode.entries,
                selected = settings.themeMode,
                onSelect = { mode -> Store.updateSettings { it.copy(themeMode = mode) } },
                label = { it.label }
            )
            Spacer(Modifier.height(16.dp))
            MText(text = "强调色", color = p.text, style = QsType.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentColor.entries.forEach { accent ->
                    AccentSwatch(
                        accent = accent,
                        selected = settings.accent == accent,
                        onClick = { Store.updateSettings { it.copy(accent = accent) } }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            RowLabel(
                label = "玻璃强度",
                value = "${(glass * 100).roundToInt()}% · " + if (glass >= 0.85f) "标准" else if (glass >= 0.6f) "轻薄" else "省电"
            )
            Spacer(Modifier.height(2.dp))
            MiuixSlider(
                value = glass,
                valueRange = 0.4f..1.0f,
                onValueChange = { glass = it },
                onValueChangeFinished = {
                    Store.updateSettings { it.copy(glassIntensity = glass) }
                }
            )
            Spacer(Modifier.height(4.dp))
            MText(
                text = "低端机可以调低，玻璃更淡、光斑更弱，滑动更省电。",
                color = p.faint,
                style = QsType.tiny
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun AccentSwatch(accent: AccentColor, selected: Boolean, onClick: () -> Unit) {
    val color = accentColorOf(accent)
    val p = QsTheme.palette
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) p.text else p.strokeTop.copy(alpha = 0.4f),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                QsIcon(QsIcon.CHECK, size = 15.dp, tint = Color.Black.copy(alpha = 0.7f), strokeFraction = 0.16f)
            }
        }
        Spacer(Modifier.height(5.dp))
        MText(
            text = accent.label,
            color = if (selected) p.text else p.dim,
            style = QsType.tiny,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}
