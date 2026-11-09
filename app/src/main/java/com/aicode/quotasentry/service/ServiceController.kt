package com.aicode.quotasentry.service

import android.app.Activity
import android.content.Context
import com.aicode.quotasentry.core.ProviderId

/** 全局访问点：UI / MainActivity 只依赖这里。 */
object ServiceController : ServiceBridge {

    private val impl: ServiceBridge get() = ServiceBridgeImpl

    override fun start(context: Context) = impl.start(context)
    override fun stop(context: Context) = impl.stop(context)
    override fun isRunning(context: Context): Boolean = impl.isRunning(context)
    override fun sendNow(context: Context, provider: ProviderId) = impl.sendNow(context, provider)
    override fun refreshQuota(context: Context, provider: ProviderId?) = impl.refreshQuota(context, provider)
    override fun status(context: Context): BackgroundStatus = impl.status(context)
    override fun requestBatteryExemption(activity: Activity): Boolean = impl.requestBatteryExemption(activity)
    override fun isIgnoringBatteryOptimizations(context: Context): Boolean = impl.isIgnoringBatteryOptimizations(context)
    override fun canScheduleExactAlarms(context: Context): Boolean = impl.canScheduleExactAlarms(context)
    override fun openExactAlarmSettings(activity: Activity) = impl.openExactAlarmSettings(activity)
    override fun areNotificationsEnabled(context: Context): Boolean = impl.areNotificationsEnabled(context)
    override fun openNotificationSettings(context: Context) = impl.openNotificationSettings(context)
    override fun openAutoStartSettings(context: Context): Boolean = impl.openAutoStartSettings(context)
    override fun openAppDetails(context: Context) = impl.openAppDetails(context)
}
