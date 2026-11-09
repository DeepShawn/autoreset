package com.aicode.quotasentry.service

import android.app.Activity
import android.content.Context
import com.aicode.quotasentry.core.ProviderId

/** 后台可靠性的体检结果，设置页展示用。 */
data class BackgroundStatus(
    val serviceRunning: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val exactAlarms: Boolean = false,
    val notifications: Boolean = false,
    val backgroundEnabled: Boolean = true
) {
    val healthyCount: Int
        get() = listOf(serviceRunning, batteryUnrestricted, exactAlarms, notifications).count { it }
}

/**
 * UI 与后台服务之间的唯一入口。实现见同目录实现类（由 background 方向落成）。
 */
interface ServiceBridge {

    /** 启动常驻服务（用户开启后台保活时调用）。 */
    fun start(context: Context)

    /** 停止常驻服务（用户关闭开关时调用）。 */
    fun stop(context: Context)

    /** 服务当前是否在运行。 */
    fun isRunning(context: Context): Boolean

    /** 立即对指定 Provider 执行一次保活发送。 */
    fun sendNow(context: Context, provider: ProviderId)

    /** 立即刷新指定 Provider 的额度（null = 全部）。 */
    fun refreshQuota(context: Context, provider: ProviderId? = null)

    fun status(context: Context): BackgroundStatus

    /** 跳系统电池优化白名单授权。 */
    fun requestBatteryExemption(activity: Activity): Boolean

    fun isIgnoringBatteryOptimizations(context: Context): Boolean

    fun canScheduleExactAlarms(context: Context): Boolean

    fun openExactAlarmSettings(activity: Activity)

    fun areNotificationsEnabled(context: Context): Boolean

    fun openNotificationSettings(context: Context)

    /** 尝试打开 MIUI/HyperOS 的自启动管理页；失败返回 false。 */
    fun openAutoStartSettings(context: Context): Boolean

    fun openAppDetails(context: Context)
}
