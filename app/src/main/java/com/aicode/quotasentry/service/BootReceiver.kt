package com.aicode.quotasentry.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aicode.quotasentry.core.Store

/**
 * 开机 / 应用更新广播：如果用户开着后台保活且至少连接过一个账号，
 * 重启前台服务并重排兜底闹钟。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext
        // 用户存储未解锁时 Store 还不可用，等解锁后的 BOOT_COMPLETED 即可
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val settings = Store.settingsNow()
        if (!settings.backgroundEnabled) {
            AlarmScheduler.cancel(app)
            return
        }
        val hasLinked = Store.accounts.value.values.any { it.linked }
        if (!hasLinked) {
            AlarmScheduler.cancel(app)
            return
        }
        ServiceActions.startService(app, ServiceActions.ACTION_START)
        AlarmScheduler.scheduleNext(app)
    }
}
