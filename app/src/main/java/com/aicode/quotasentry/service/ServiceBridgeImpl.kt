package com.aicode.quotasentry.service

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SysSettings
import com.aicode.quotasentry.core.DefaultMessages
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [ServiceBridge] 的真实实现。
 *
 * UI / MainActivity 只通过 [ServiceController] 访问这里；所有写 Store 的设置操作都在这里补齐，
 * 保证「用户点开关」与「settings.backgroundEnabled」始终一致。
 */
object ServiceBridgeImpl : ServiceBridge {

    /** 系统拒绝后台启动前台服务时，用进程内一次性协程兜底，至少让用户的点击有结果。 */
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------------------ 启停

    override fun start(context: Context) {
        val app = context.applicationContext
        Store.init(app)
        // 用户主动开启：同步总开关（UI 也可能会写一次，这里保证一致性）
        Store.updateSettings { it.copy(backgroundEnabled = true) }
        val started = ServiceActions.startService(app, ServiceActions.ACTION_START)
        AlarmScheduler.scheduleNext(app)
        if (!started) {
            // 极少见：后台启动前台服务被拒。连续尝试一次普通 startService 作为降级。
            runCatching {
                app.startService(ServiceActions.intent(app, ServiceActions.ACTION_START))
            }
        }
    }

    override fun stop(context: Context) {
        val app = context.applicationContext
        Store.init(app)
        // 先关总开关：服务 onDestroy 看到它就不会再安排重启兜底
        Store.updateSettings { it.copy(backgroundEnabled = false) }
        ServiceActions.stopService(app)
        AlarmScheduler.cancel(app)
        Store.markServiceRunning(false)
        NotificationHelper.cancelAllAlerts(app)
    }

    override fun isRunning(context: Context): Boolean {
        // 同进程内直接看服务实例，避免上次进程被回收后 Store 里残留的 running=true
        val active = KeepAliveService.isActive()
        if (!active && Store.serviceRunning.value) {
            // 进程被回收且服务没被系统拉起：顺手把残留标记修正掉
            Store.markServiceRunning(false)
        }
        return active
    }

    // ------------------------------------------------------------ 立即操作

    override fun sendNow(context: Context, provider: ProviderId) {
        val app = context.applicationContext
        val started = ServiceActions.sendCommand(app, ServiceActions.ACTION_SEND_NOW, provider)
        if (!started) {
            fallbackScope.launch {
                runCatching {
                    val message = Store.rules.value
                        .firstOrNull { it.provider == provider }
                        ?.message
                        ?.takeIf { it.isNotBlank() }
                        ?: DefaultMessages.forProvider(provider)
                    // 多账号：该 Provider 下每个已连接账号都要发
                    Store.linkedAccounts()
                        .filter { it.provider == provider }
                        .forEach { account -> SendEngine.send(app, account, message) }
                }
            }
        }
    }

    override fun refreshQuota(context: Context, provider: ProviderId?) {
        val app = context.applicationContext
        val started = ServiceActions.sendCommand(app, ServiceActions.ACTION_REFRESH_QUOTA, provider)
        if (!started) {
            fallbackScope.launch {
                runCatching {
                    val targets = if (provider == null) {
                        Store.linkedAccounts()
                    } else {
                        Store.linkedAccounts().filter { it.provider == provider }
                    }
                    targets.forEach { account -> SendEngine.refresh(app, account, record = true) }
                }
            }
        }
    }

    // ------------------------------------------------------------ 体检

    override fun status(context: Context): BackgroundStatus = BackgroundStatus(
        serviceRunning = isRunning(context),
        batteryUnrestricted = isIgnoringBatteryOptimizations(context),
        exactAlarms = canScheduleExactAlarms(context),
        notifications = areNotificationsEnabled(context),
        backgroundEnabled = Store.settingsNow().backgroundEnabled
    )

    // ------------------------------------------------------------ 电池优化

    override fun requestBatteryExemption(activity: Activity): Boolean {
        val packageUri = Uri.parse("package:${activity.packageName}")
        val direct = Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        val directOk = runCatching {
            activity.startActivity(direct)
            true
        }.getOrDefault(false)
        if (directOk) return true
        // 部分 ROM 没有这个授权页：退到电池优化列表页
        val list = Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val listOk = runCatching {
            activity.startActivity(list)
            true
        }.getOrDefault(false)
        if (listOk) return true
        openAppDetails(activity)
        return false
    }

    override fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return runCatching { manager.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    // ------------------------------------------------------------ 精确闹钟

    override fun canScheduleExactAlarms(context: Context): Boolean =
        AlarmScheduler.canScheduleExactAlarms(context)

    override fun openExactAlarmSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(
                SysSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${activity.packageName}")
            )
            val opened = runCatching {
                activity.startActivity(intent)
                true
            }.getOrDefault(false)
            if (opened) return
        }
        // Android 12 以下没有单独开关，直接打开应用详情页
        openAppDetails(activity)
    }

    // ------------------------------------------------------------ 通知权限

    override fun areNotificationsEnabled(context: Context): Boolean =
        NotificationHelper.areNotificationsEnabled(context)

    override fun openNotificationSettings(context: Context) {
        val app = context.applicationContext
        val intent = Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(SysSettings.EXTRA_APP_PACKAGE, app.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching {
            app.startActivity(intent)
            true
        }.getOrDefault(false)
        if (!opened) openAppDetails(app)
    }

    // ------------------------------------------------------------ 自启动 / 应用详情

    /**
     * 打开 MIUI / HyperOS 的自启动管理页。
     * 依次尝试 miui 的 action 与已知 Activity，找不到就退回应用详情页并返回 false。
     */
    override fun openAutoStartSettings(context: Context): Boolean {
        val app = context.applicationContext
        val candidates = buildList {
            add(Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT))
            add(
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                )
            )
            add(
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                    )
                )
                    .putExtra("package_name", app.packageName)
                    .putExtra("package_label", "额度哨兵")
            )
            // 个别 HyperOS 版本的自启动页
            add(
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartDetailManagementActivity"
                    )
                )
            )
            // 其他常见 ROM 的入口，找不到会被 resolveActivity 过滤掉
            add(
                Intent().setComponent(
                    ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                )
            )
            add(
                Intent().setComponent(
                    ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                )
            )
        }
        for (candidate in candidates) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvable = runCatching {
                app.packageManager.resolveActivity(candidate, 0) != null
            }.getOrDefault(false)
            if (!resolvable) continue
            val opened = runCatching {
                app.startActivity(candidate)
                true
            }.getOrDefault(false)
            if (opened) return true
        }
        openAppDetails(app)
        return false
    }

    override fun openAppDetails(context: Context) {
        val app = context.applicationContext
        val intent = Intent(
            SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${app.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }
}
