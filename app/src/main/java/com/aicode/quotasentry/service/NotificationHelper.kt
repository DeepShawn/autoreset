package com.aicode.quotasentry.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aicode.quotasentry.MainActivity
import com.aicode.quotasentry.R
import com.aicode.quotasentry.core.Formatters
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.Settings

/**
 * 通知集中管理：
 *
 * - `service` 渠道（低优先级、常驻）：显示已连接家数与下一次发送时间，前台服务必须挂它；
 * - `alert` 渠道（默认重要性）：发送结果、连续失败、额度刷新失败。
 *
 * 通知点击统一打开 MainActivity；按钮统一走 [ActionReceiver]，不直接在这里执行业务。
 */
object NotificationHelper {

    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_ALERT = "alert"

    /** 前台服务常驻通知 id。 */
    const val ID_SERVICE = 1001

    /** 通知按钮的 PendingIntent request code（固定值，重复设置会覆盖而不是叠加）。 */
    private const val REQ_CONTENT = 1101
    private const val REQ_SEND_NOW = 1102
    private const val REQ_REFRESH = 1103
    private const val REQ_PAUSE = 1104

    private const val ALERT_ID_BASE = 2100
    private const val RESULT_ID_BASE = 2300

    /** 建立两个通知渠道（minSdk 26，渠道 API 一定有）。 */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            val service = NotificationChannel(
                CHANNEL_SERVICE,
                "后台守护",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "常驻通知：显示已连接账号与下一次发送时间"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val alert = NotificationChannel(
                CHANNEL_ALERT,
                "发送与额度提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "保活发送结果、连续发送失败、额度刷新失败等提醒"
                setShowBadge(true)
            }
            manager.createNotificationChannel(service)
            manager.createNotificationChannel(alert)
        }
    }

    // ------------------------------------------------------------ 常驻通知

    /**
     * 构建前台服务常驻通知。
     *
     * @param connectedCount 已连接的账号家数
     * @param nextSendAt 下一次计划发送时刻（0 = 暂无计划）
     * @param pausedUntil 暂停到何时（0 = 未暂停）
     * @param detailed false 表示用户关掉了「常驻通知」详情，只保留系统要求的最小文案
     */
    fun buildServiceNotification(
        context: Context,
        connectedCount: Int,
        nextSendAt: Long,
        pausedUntil: Long,
        now: Long,
        detailed: Boolean = true
    ): Notification {
        val text = if (detailed) {
            serviceText(connectedCount, nextSendAt, pausedUntil, now)
        } else {
            "后台守护中"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("额度哨兵 · 守护中")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(mainActivityIntent(context))
        if (detailed) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            builder.addAction(0, "立即续命", actionIntent(context, ServiceActions.ACTION_SEND_NOW_ALL, REQ_SEND_NOW))
            builder.addAction(0, "刷新额度", actionIntent(context, ServiceActions.ACTION_REFRESH_QUOTA, REQ_REFRESH))
            builder.addAction(0, "暂停 2 小时", actionIntent(context, ServiceActions.ACTION_PAUSE_2H, REQ_PAUSE))
        }
        return builder.build()
    }

    /** 常驻通知的正文文案。 */
    fun serviceText(connectedCount: Int, nextSendAt: Long, pausedUntil: Long, now: Long): String {
        if (pausedUntil > now) {
            return "已暂停，${Formatters.clock(pausedUntil)} 自动恢复"
        }
        if (connectedCount <= 0) {
            return "还没有连接的账号 · 打开应用连接"
        }
        return if (nextSendAt > now) {
            "已连接 $connectedCount 家 · 下次 ${Formatters.clock(nextSendAt)}"
        } else {
            "已连接 $connectedCount 家 · 暂无启用的计划"
        }
    }

    // ------------------------------------------------------------ 提醒通知

    /** 一次发送的结果通知（成功 / 失败）。同一 Provider 复用同一个 id，不会堆积。 */
    fun notifySendResult(context: Context, provider: ProviderId, ok: Boolean, detail: String) {
        val title = if (ok) "${provider.label} 已续命" else "${provider.label} 发送失败"
        val text = when {
            ok && detail.isNotBlank() -> detail
            ok -> "保活消息已发送，额度窗口已续上"
            detail.isNotBlank() -> detail
            else -> "请检查网络或登录状态"
        }
        // 成功不打扰（静默），失败才带提示音
        alert(context, RESULT_ID_BASE + provider.ordinal, title, text, actions = !ok, silent = ok)
    }

    /** 连续失败提醒（每 3 次一条，不轰炸）。 */
    fun notifyFailureAlert(context: Context, provider: ProviderId, consecutiveFailures: Int, detail: String) {
        val text = buildString {
            append(detail.ifBlank { "请检查网络或登录状态" })
            append(" · 连续 $consecutiveFailures 次，建议打开应用检查账号")
        }
        alert(
            context,
            ALERT_ID_BASE + provider.ordinal,
            "${provider.label} 连续 $consecutiveFailures 次发送失败",
            text,
            actions = true
        )
    }

    /** 额度刷新失败提醒（手动刷新时给可见反馈）。 */
    fun notifyRefreshFailure(context: Context, provider: ProviderId, detail: String) {
        alert(
            context,
            ALERT_ID_BASE + provider.ordinal,
            "${provider.label} 额度刷新失败",
            detail.ifBlank { "请检查网络或代理设置" },
            actions = true
        )
    }

    /** 通用操作提示（例如启动失败、需要用户手动处理）。 */
    fun notifyNotice(context: Context, id: Int, title: String, text: String) {
        alert(context, id, title, text, actions = false)
    }

    /**
     * 发一条 alert 渠道通知。
     *
     * @param actions 是否带「立即续命 / 刷新额度 / 暂停 2 小时」按钮
     */
    fun alert(
        context: Context,
        id: Int,
        title: String,
        text: String,
        actions: Boolean = false,
        silent: Boolean = false
    ) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(mainActivityIntent(context))
        if (silent) builder.setSilent(true)
        if (actions) {
            builder.addAction(0, "立即续命", actionIntent(context, ServiceActions.ACTION_SEND_NOW_ALL, REQ_SEND_NOW))
            builder.addAction(0, "刷新额度", actionIntent(context, ServiceActions.ACTION_REFRESH_QUOTA, REQ_REFRESH))
            builder.addAction(0, "暂停 2 小时", actionIntent(context, ServiceActions.ACTION_PAUSE_2H, REQ_PAUSE))
        }
        post(context, id, builder.build())
    }

    /** 用户关掉通知时静默失败，不抛异常。 */
    fun post(context: Context, id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context.applicationContext).notify(id, notification) }
    }

    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context.applicationContext).cancel(id) }
    }

    fun cancelAllAlerts(context: Context) {
        ProviderId.ordered.forEach { provider ->
            cancel(context, ALERT_ID_BASE + provider.ordinal)
            cancel(context, RESULT_ID_BASE + provider.ordinal)
        }
    }

    /** 通知权限是否开启（Android 13+ 需要用户授权）。 */
    fun areNotificationsEnabled(context: Context): Boolean =
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).areNotificationsEnabled()
        }.getOrDefault(false)

    /** 当前 settings 是否允许发详情型常驻通知。 */
    fun serviceNotificationDetailed(settings: Settings): Boolean = settings.ongoingNotification

    // ------------------------------------------------------------ PendingIntent

    private fun mainActivityIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context.applicationContext,
        REQ_CONTENT,
        Intent(context.applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            requestCode,
            Intent(context.applicationContext, ActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
