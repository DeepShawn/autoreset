package com.aicode.quotasentry.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.aicode.quotasentry.core.DefaultMessages
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.ScheduleMath
import com.aicode.quotasentry.core.ScheduleRule
import com.aicode.quotasentry.core.Settings
import com.aicode.quotasentry.core.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 后台常驻服务。
 *
 * 设计要点：
 * - specialUse 前台服务：`onCreate` 里立刻 `startForeground`，满足 Android 12+ 的 5 秒限制；
 * - `START_STICKY` + manifest 的 `stopWithTask=false`：划掉任务栏不退出，`onTaskRemoved` 再用
 *   AlarmManager 5 秒后兜底重启；
 * - 服务内协程每 30 秒 tick 一次（没有连接账号时空转间隔放宽到 5 分钟），到点的计划执行发送；
 * - 网络请求期间才拿 WakeLock（2 分钟超时），其余时间只 `delay`，不持锁长睡；
 * - 连续失败 3 次才提醒一次，成功后计数清零，避免轰炸通知；
 * - 用户关闭总开关（`settings.backgroundEnabled=false`）时 `stopSelf` 并清掉兜底闹钟。
 */
class KeepAliveService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    /** 所有会写 Store / 发网络请求的操作串行执行，避免 tick 与手动命令撞车。 */
    private val operationMutex = Mutex()

    private val failureStreak = ConcurrentHashMap<ProviderId, Int>()
    private val lastFailureAlertAt = ConcurrentHashMap<ProviderId, Long>()

    @Volatile
    private var pausedUntil: Long = 0L

    @Volatile
    private var lastRefreshAt: Long = 0L

    @Volatile
    private var lastNotificationAt: Long = 0L

    @Volatile
    private var foregroundStarted: Boolean = false

    @Volatile
    private var stopRequested: Boolean = false

    override fun onCreate() {
        super.onCreate()
        // 服务可能被系统在 Application 之后冷启动拉起，这里确保 Store 就绪
        Store.init(applicationContext)
        NotificationHelper.ensureChannels(this)
        // 必须在 onCreate 内立刻 startForeground，避免 startForegroundService 后 5 秒超时
        promoteToForeground()
        Store.markServiceRunning(true)
        active = true
        AlarmScheduler.scheduleNext(this)
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 兜底：极端情况下（例如系统重建）确保前台通知一定已挂上
        if (!foregroundStarted) promoteToForeground()

        val action = intent?.action
        when (action) {
            ServiceActions.ACTION_START, ServiceActions.ACTION_TICK -> {
                serviceScope.launch { tick() }
            }

            ServiceActions.ACTION_SEND_NOW -> {
                serviceScope.launch { sendNow(ServiceActions.providerOf(intent)) }
            }

            ServiceActions.ACTION_SEND_NOW_ALL -> {
                serviceScope.launch { sendNow(null) }
            }

            ServiceActions.ACTION_REFRESH_QUOTA -> {
                serviceScope.launch { refreshQuota(ServiceActions.providerOf(intent)) }
            }

            ServiceActions.ACTION_PAUSE_2H -> {
                pausedUntil = System.currentTimeMillis() + PAUSE_MILLIS
                updateOngoingNotification(force = true)
            }

            ServiceActions.ACTION_STOP -> {
                stopRequested = true
                stopSelf()
            }

            else -> {
                // 无 action（系统重建 START_STICKY）时按普通启动处理
                if (intent == null) serviceScope.launch { tick() }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 划掉任务栏：不退出，5 秒后 AlarmManager 兜底把服务拉回来。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!stopRequested && Store.settingsNow().backgroundEnabled) {
            AlarmScheduler.scheduleRestartSoon(this, 5_000L)
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        loopJob = null
        serviceScope.cancel()
        active = false
        Store.markServiceRunning(false)
        if (Store.settingsNow().backgroundEnabled) {
            // 被系统回收：补一次重启兜底（正常停止时 backgroundEnabled 已为 false）
            if (!stopRequested) AlarmScheduler.scheduleRestartSoon(this, 5_000L)
        } else {
            AlarmScheduler.cancel(this)
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    // ------------------------------------------------------------ 前台通知

    private fun promoteToForeground() {
        runCatching {
            val settings = Store.settingsNow()
            val notification = buildOngoingNotification(settings)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationHelper.ID_SERVICE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NotificationHelper.ID_SERVICE, notification)
            }
            foregroundStarted = true
            lastNotificationAt = System.currentTimeMillis()
            if (!NotificationHelper.areNotificationsEnabled(this)) {
                // 通知权限被关：前台服务仍必须挂通知（系统任务管理器可见），这里只记日志
                Log.w(TAG, "通知权限未开启，常驻通知不可见")
            }
        }.onFailure { Log.w(TAG, "startForeground 失败：${it.message}") }
    }

    private fun buildOngoingNotification(settings: Settings): android.app.Notification {
        val now = System.currentTimeMillis()
        val connected = Store.accounts.value.values.count { it.linked }
        val nextAt = nextSendAt(now)
        return NotificationHelper.buildServiceNotification(
            context = this,
            connectedCount = connected,
            nextSendAt = nextAt,
            pausedUntil = pausedUntil,
            now = now,
            detailed = NotificationHelper.serviceNotificationDetailed(settings)
        )
    }

    /** 每 5 分钟（或状态变化时）刷新常驻通知内容。 */
    private fun updateOngoingNotification(force: Boolean = false, now: Long = System.currentTimeMillis()) {
        if (!foregroundStarted) return
        if (!force && now - lastNotificationAt < NOTIFICATION_REFRESH_MILLIS) return
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                ?: return
            manager.notify(
                NotificationHelper.ID_SERVICE,
                buildOngoingNotification(Store.settingsNow())
            )
            lastNotificationAt = now
        }.onFailure { Log.w(TAG, "更新常驻通知失败：${it.message}") }
    }

    /** 下一次计划发送时刻（epoch ms），没有未来计划返回 0。 */
    private fun nextSendAt(now: Long): Long {
        val window = Store.settingsNow().windowMillis()
        return Store.rules.value
            .filter { it.enabled }
            .map { rule ->
                if (rule.nextRunAt > 0L) {
                    rule.nextRunAt
                } else {
                    ScheduleMath.computeNext(
                        rule,
                        now,
                        Store.quota(rule.provider).officialResetAt,
                        window
                    )
                }
            }
            .filter { it > now }
            .minOrNull() ?: 0L
    }

    // ------------------------------------------------------------ 主循环

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = serviceScope.launch {
            while (isActive) {
                tick()
                val idle = Store.accounts.value.values.none { it.linked }
                delay(if (idle) IDLE_TICK_MILLIS else TICK_MILLIS)
            }
        }
    }

    /** 一轮调度：检查总开关、到期计划、自动刷新、通知与闹钟。 */
    private suspend fun tick() {
        try {
            operationMutex.withLock { tickLocked() }
        } catch (t: Throwable) {
            Log.w(TAG, "tick 异常：${t.message}")
        }
    }

    private suspend fun tickLocked() {
        val now = System.currentTimeMillis()
        val settings = Store.settingsNow()

        // 用户关闭了后台保活总开关：停止自身并清掉所有调度
        if (!settings.backgroundEnabled) {
            stopRequested = true
            runCatching { stopSelf() }
            return
        }

        // 暂停到期：立刻恢复发送并刷新通知文案
        if (pausedUntil in 1L..now) {
            pausedUntil = 0L
            updateOngoingNotification(force = true)
        }

        val hasLinked = Store.accounts.value.values.any { it.linked }
        if (!hasLinked) {
            // 没有连接账号：保持常驻但空转，5 分钟一次（见 startLoop 的 idle 间隔）
            updateOngoingNotification(now = now)
            AlarmScheduler.scheduleNext(this, now)
            return
        }

        runDueRules(now, settings)
        maybeAutoRefresh(now, settings)
        updateOngoingNotification(now = now)
        AlarmScheduler.scheduleNext(this, now)
    }

    /** 逐条检查到点的计划并执行发送。 */
    private suspend fun runDueRules(now: Long, settings: Settings) {
        if (pausedUntil > now) return
        val window = settings.windowMillis()
        for (rule in Store.rules.value.filter { it.enabled }) {
            val account = Store.account(rule.provider)
            if (!account.linked) continue

            val nextAt = rule.nextRunAt
            if (nextAt <= 0L) {
                // 首次运行 / 刚启用：只计算下一次，不立刻补发
                val computed = ScheduleMath.computeNext(
                    rule,
                    now,
                    Store.quota(rule.provider).officialResetAt,
                    window
                )
                Store.updateRule(rule.id) { it.copy(nextRunAt = computed) }
                continue
            }
            if (!BackgroundMath.isRuleDue(rule, now, nextAt)) continue
            // 关机/休眠太久，已错过窗口：重排而不是补发，避免一次发一堆
            if (BackgroundMath.isStaleMiss(now, nextAt, window)) {
                val computed = ScheduleMath.computeNext(
                    rule,
                    now,
                    Store.quota(rule.provider).officialResetAt,
                    window
                )
                Store.updateRule(rule.id) { it.copy(nextRunAt = computed) }
                continue
            }
            if (settings.wifiOnly && !isOnWifi()) continue

            executeRule(rule, settings, now)
        }
    }

    /** 执行一条计划：发送 → 成功后按需刷新额度 → 推进下一次计划 → 通知。 */
    private suspend fun executeRule(rule: ScheduleRule, settings: Settings, now: Long) {
        // 重新读一次，UI 可能刚改过这条规则
        val current = Store.rules.value.firstOrNull { it.id == rule.id } ?: return
        if (!current.enabled) return
        if (!BackgroundMath.isRuleDue(current, now, current.nextRunAt)) return

        val provider = current.provider
        if (!Store.account(provider).linked) return
        val message = current.message.ifBlank { DefaultMessages.forProvider(provider) }

        val result = SendEngine.send(this, provider, message)
        val doneAt = System.currentTimeMillis()

        // 规则要求同步额度时先刷新：拿到新的窗口重置时刻，FOLLOW_RESET 的下次锚点才准
        if (result.ok && current.syncQuota) {
            SendEngine.refresh(this, provider, record = true)
            lastRefreshAt = System.currentTimeMillis()
        }

        // 推进计划：无论成功失败都重排，避免失败后每 30 秒重试轰炸
        val label = if (result.ok) "成功" else "失败：${result.detail}"
        Store.updateRule(current.id) { latest ->
            val ran = latest.copy(lastRunAt = doneAt, lastResult = label)
            ran.copy(
                nextRunAt = ScheduleMath.computeNext(
                    ran,
                    doneAt,
                    Store.quota(provider).officialResetAt,
                    settings.windowMillis()
                )
            )
        }

        onSendResult(provider, message, result, settings)
        updateOngoingNotification(force = true)
        AlarmScheduler.scheduleNext(this)
    }

    /** 按 `autoRefreshMinutes` 定期刷新官方额度。 */
    private suspend fun maybeAutoRefresh(now: Long, settings: Settings) {
        val interval = settings.refreshMillis()
        if (interval <= 0L) return // 0 = 只手动刷新
        if (lastRefreshAt <= 0L) {
            // 服务刚起来：先刷一轮，让 UI 立刻拿到较新的官方额度
            lastRefreshAt = now - interval
        }
        if (now - lastRefreshAt < interval) return
        lastRefreshAt = now
        val targets = Store.accounts.value.values.filter { it.linked }.map { it.provider }
        if (targets.isEmpty()) return
        targets.forEach { provider ->
            runCatching { SendEngine.refresh(this, provider, record = true) }
        }
    }

    // ------------------------------------------------------------ 手动命令

    /** 立即续命：provider 为 null 时对所有已连接账号各发一条。 */
    private suspend fun sendNow(provider: ProviderId?) {
        try {
            operationMutex.withLock {
                val targets = provider?.let { listOf(it) }
                    ?: ProviderId.ordered.filter { Store.account(it).linked }
                if (targets.isEmpty()) {
                    NotificationHelper.notifyNotice(
                        this,
                        NOTICE_ID,
                        "还没有连接的账号",
                        "打开额度哨兵，在设置 → 账号连接里先连接一个账号"
                    )
                    return@withLock
                }
                val settings = Store.settingsNow()
                coroutineSendTargets(targets, settings)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "手动发送失败：${t.message}")
        }
        updateOngoingNotification(force = true)
        AlarmScheduler.scheduleNext(this)
    }

    private suspend fun coroutineSendTargets(targets: List<ProviderId>, settings: Settings) {
        for (provider in targets) {
            if (!Store.account(provider).linked) continue
            val rule = Store.rules.value.firstOrNull { it.provider == provider }
            val message = rule?.message?.takeIf { it.isNotBlank() }
                ?: DefaultMessages.forProvider(provider)
            val result = SendEngine.send(this, provider, message)
            if (result.ok && rule?.syncQuota == true) {
                SendEngine.refresh(this, provider, record = true)
            }
            onSendResult(provider, message, result, settings)
            if (result.ok) {
                // 手动续命已经把窗口续上，顺手把计划的锚点推到下一次，避免紧接着又自动发
                val doneAt = System.currentTimeMillis()
                rule?.let { r ->
                    Store.updateRule(r.id) { latest ->
                        val ran = latest.copy(lastRunAt = doneAt, lastResult = "成功（手动）")
                        ran.copy(
                            nextRunAt = ScheduleMath.computeNext(
                                ran,
                                doneAt,
                                Store.quota(provider).officialResetAt,
                                settings.windowMillis()
                            )
                        )
                    }
                }
            }
        }
    }

    /** 立即刷新额度：provider 为 null 时刷新所有已连接账号。 */
    private suspend fun refreshQuota(provider: ProviderId?) {
        try {
            operationMutex.withLock {
                val targets = provider?.let { listOf(it) }
                    ?: ProviderId.ordered.filter { Store.account(it).linked }
                targets.forEach { target ->
                    if (!Store.account(target).linked) return@forEach
                    val result = SendEngine.refresh(this, target, record = true)
                    // 手动刷新失败给可见反馈；自动刷新只写时间线
                    if (!result.ok) {
                        NotificationHelper.notifyRefreshFailure(this, target, result.detail)
                    }
                }
                lastRefreshAt = System.currentTimeMillis()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "手动刷新失败：${t.message}")
        }
        updateOngoingNotification(force = true)
    }

    // ------------------------------------------------------------ 结果处理

    /** 发送结果通知策略：成功按设置提醒；失败连续 3 次才提醒，未授权额外限流提醒。 */
    private fun onSendResult(
        provider: ProviderId,
        message: String,
        result: SendEngine.Result,
        settings: Settings
    ) {
        val now = System.currentTimeMillis()
        if (result.ok) {
            failureStreak.remove(provider)
            if (settings.resultNotification) {
                NotificationHelper.notifySendResult(
                    this,
                    provider,
                    ok = true,
                    detail = "${provider.label} 已发送「$message」"
                )
            }
            return
        }

        val count = (failureStreak[provider] ?: 0) + 1
        failureStreak[provider] = count
        val lastAlert = lastFailureAlertAt[provider] ?: 0L
        val unauthorizedAlert = result.unauthorized && now - lastAlert > UNAUTHORIZED_ALERT_GAP_MILLIS
        if (BackgroundMath.shouldAlertFailure(count) || unauthorizedAlert) {
            lastFailureAlertAt[provider] = now
            NotificationHelper.notifyFailureAlert(this, provider, count, result.detail)
        }
    }

    // ------------------------------------------------------------ 网络环境

    /** 只允许 Wi-Fi / 以太网时发送（设置里可开）。拿不到状态时按“有网”处理，避免永远不发。 */
    private fun isOnWifi(): Boolean {
        return try {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (t: Throwable) {
            true
        }
    }

    companion object {
        private const val TAG = "QuotaSentryService"

        /** 有账号时的 tick 间隔。 */
        private const val TICK_MILLIS = 30_000L

        /** 没有连接账号时的空转间隔。 */
        private const val IDLE_TICK_MILLIS = 5L * 60_000L

        /** 常驻通知内容刷新间隔。 */
        private const val NOTIFICATION_REFRESH_MILLIS = 5L * 60_000L

        /** 「暂停 2 小时」按钮的时长。 */
        private const val PAUSE_MILLIS = 2L * 60L * 60_000L

        /** Cookie 失效这类“必须用户操作”的失败，最多每小时提醒一次。 */
        private const val UNAUTHORIZED_ALERT_GAP_MILLIS = 60L * 60_000L

        private const val NOTICE_ID = 2501

        /** 进程内服务实例是否存活（跨进程重启会自然归零）。 */
        @Volatile
        private var active = false

        fun isActive(): Boolean = active
    }
}
