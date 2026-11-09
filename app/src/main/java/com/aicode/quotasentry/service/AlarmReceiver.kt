package com.aicode.quotasentry.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aicode.quotasentry.core.Store

/**
 * AlarmManager 兜底唤醒接收器：
 * - [AlarmScheduler.ACTION_ALARM]：计划前 1 分钟的兜底唤醒；
 * - [AlarmScheduler.ACTION_RESTART]：划掉任务栏 / 进程被回收后的 5 秒重启。
 *
 * 一律通过 `startForegroundService` 把命令交给 [KeepAliveService]，接收器本身不做业务。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!Store.settingsNow().backgroundEnabled) {
            AlarmScheduler.cancel(app)
            return
        }
        val started = ServiceActions.startService(app, ServiceActions.ACTION_TICK)
        if (!started) {
            // Android 12+ 极端情况下会拒绝后台启动前台服务：10 分钟后再试一次
            Log.w("QuotaSentry", "AlarmReceiver 启动前台服务被拒，稍后重试")
            AlarmScheduler.scheduleRestartSoon(app, 10L * 60_000L)
        } else {
            AlarmScheduler.scheduleNext(app)
        }
    }
}
