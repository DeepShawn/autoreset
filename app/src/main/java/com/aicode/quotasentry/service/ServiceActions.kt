package com.aicode.quotasentry.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.aicode.quotasentry.core.ProviderId

/**
 * 服务命令常量 + 启动辅助。
 *
 * 通知按钮、AlarmReceiver、ServiceBridgeImpl 都只通过这里给 KeepAliveService 下命令，
 * 避免各处重复拼 Intent。
 */
object ServiceActions {

    /** 启动服务（用户打开总开关 / 冷启动补拉）。 */
    const val ACTION_START = "com.aicode.quotasentry.action.START"

    /** 闹钟 / 重启兜底唤醒：立刻跑一轮 tick。 */
    const val ACTION_TICK = "com.aicode.quotasentry.action.TICK"

    /** 立即为指定 Provider 发一条（无 provider extra 则发给全部已连接账号）。 */
    const val ACTION_SEND_NOW = "com.aicode.quotasentry.action.SEND_NOW"

    /** 立即续命：给所有已连接账号各发一条。 */
    const val ACTION_SEND_NOW_ALL = "com.aicode.quotasentry.action.SEND_NOW_ALL"

    /** 立即刷新官方额度（无 provider extra 则全部已连接账号）。 */
    const val ACTION_REFRESH_QUOTA = "com.aicode.quotasentry.action.REFRESH_QUOTA"

    /** 暂停 2 小时发送（服务继续常驻，到点自动恢复）。 */
    const val ACTION_PAUSE_2H = "com.aicode.quotasentry.action.PAUSE_2H"

    /** 用户关闭总开关：停止并取消所有调度。 */
    const val ACTION_STOP = "com.aicode.quotasentry.action.STOP"

    const val EXTRA_PROVIDER = "com.aicode.quotasentry.extra.PROVIDER"

    fun intent(context: Context, action: String, provider: ProviderId? = null): Intent =
        Intent(context.applicationContext, KeepAliveService::class.java)
            .setAction(action)
            .apply { provider?.let { putExtra(EXTRA_PROVIDER, it.key) } }

    fun providerOf(intent: Intent?): ProviderId? = ProviderId.fromKey(intent?.getStringExtra(EXTRA_PROVIDER))

    /**
     * 启动前台服务并下发一条命令。
     *
     * @return false 表示系统拒绝了后台启动前台服务（例如 Android 12+ 的启动限制），调用方需降级处理
     */
    fun startService(context: Context, action: String = ACTION_START, provider: ProviderId? = null): Boolean =
        runCatching {
            ContextCompat.startForegroundService(context.applicationContext, intent(context, action, provider))
            true
        }.getOrDefault(false)

    fun sendCommand(context: Context, action: String, provider: ProviderId? = null): Boolean =
        startService(context, action, provider)

    /** 停止服务（幂等）。停止前请先把 settings.backgroundEnabled 置 false，避免被重启兜底拉起。 */
    fun stopService(context: Context): Boolean = runCatching {
        context.applicationContext.stopService(
            Intent(context.applicationContext, KeepAliveService::class.java)
        )
        true
    }.getOrDefault(false)
}
