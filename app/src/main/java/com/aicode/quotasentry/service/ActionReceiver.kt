package com.aicode.quotasentry.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知按钮接收器：立即续命 / 刷新额度 / 暂停 2 小时。
 *
 * 按钮由 [NotificationHelper] 的 PendingIntent 触发（属于用户主动交互，
 * Android 12+ 允许此时启动前台服务）。实际逻辑全部转交 [KeepAliveService]。
 */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_ACTIONS) return
        val app = context.applicationContext
        val provider = ServiceActions.providerOf(intent)
        val started = ServiceActions.sendCommand(app, action, provider)
        if (!started) {
            NotificationHelper.notifyNotice(
                app,
                NOTICE_ID,
                "操作未执行",
                "系统拒绝了后台启动，请打开额度哨兵后重试"
            )
        }
    }

    private companion object {
        private const val NOTICE_ID = 2502

        private val SUPPORTED_ACTIONS = setOf(
            ServiceActions.ACTION_SEND_NOW,
            ServiceActions.ACTION_SEND_NOW_ALL,
            ServiceActions.ACTION_REFRESH_QUOTA,
            ServiceActions.ACTION_PAUSE_2H
        )
    }
}
