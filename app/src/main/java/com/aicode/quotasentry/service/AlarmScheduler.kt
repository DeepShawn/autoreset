package com.aicode.quotasentry.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.aicode.quotasentry.core.ScheduleMath
import com.aicode.quotasentry.core.Store

/**
 * AlarmManager 兜底调度：
 *
 * - 在最近一条计划触发前约 1 分钟唤醒服务（防止进程被系统回收后计划错过）；
 * - 没有任何计划时安排 30 分钟心跳，尽量让常驻服务被重新拉起；
 * - 有精确闹钟权限用 `setExactAndAllowWhileIdle`，没有则降级 `setAndAllowWhileIdle`（尽量准）；
 * - 每次 tick 都重算，目标时刻变化才真正重设，避免频繁设置被系统限流。
 */
object AlarmScheduler {

    /** 计划兜底唤醒。 */
    const val ACTION_ALARM = "com.aicode.quotasentry.action.ALARM"

    /** onTaskRemoved 后的 5 秒重启。 */
    const val ACTION_RESTART = "com.aicode.quotasentry.action.RESTART_SERVICE"

    private const val REQUEST_PLAN = 4301
    private const val REQUEST_RESTART = 4302

    /** 同目标 60 秒内不重复设置（系统对精确闹钟有频率限制）。 */
    private const val RESCHEDULE_GUARD_MILLIS = 60_000L

    private const val RESTART_DELAY_MILLIS = 5_000L

    @Volatile
    private var lastPlanTarget: Long = 0L

    @Volatile
    private var lastPlanSetAt: Long = 0L

    /** 是否有精确闹钟权限（Android 12+ 需要用户授权；12 以下默认有）。 */
    fun canScheduleExactAlarms(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    /** 重算并设置下一次兜底唤醒；关闭总开关时清掉所有闹钟。 */
    fun scheduleNext(context: Context, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (manager == null) return
        val settings = Store.settingsNow()
        if (!settings.backgroundEnabled) {
            cancelNext(app)
            return
        }
        val window = settings.windowMillis()
        // 计划仍然按 Provider 调度；多账号时取该 Provider 下最早的官方重置时刻做锚点
        val candidates = Store.rules.value
            .filter { it.enabled }
            .map { rule ->
                if (rule.nextRunAt > 0L) {
                    rule.nextRunAt
                } else {
                    ScheduleMath.computeNext(
                        rule,
                        now,
                        AccountSupport.nextResetAt(rule.provider, now, window),
                        window
                    )
                }
            }
        val target = BackgroundMath.nextWakeAt(now, candidates)
        if (target <= 0L) {
            cancelNext(app)
            return
        }
        // 目标没变且刚设过：跳过（每次 tick 都会调到这里）
        if (target == lastPlanTarget && now - lastPlanSetAt < RESCHEDULE_GUARD_MILLIS) {
            return
        }
        val pending = planPendingIntent(app)
        if (setAlarm(app, manager, target, pending)) {
            lastPlanTarget = target
            lastPlanSetAt = now
        }
    }

    /** 划掉任务栏 / 进程被回收后，5 秒后尝试把服务拉回来。 */
    fun scheduleRestartSoon(context: Context, delayMillis: Long = RESTART_DELAY_MILLIS) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = System.currentTimeMillis() + delayMillis.coerceAtLeast(1_000L)
        setAlarm(app, manager, triggerAt, restartPendingIntent(app))
    }

    /** 取消所有兜底闹钟（用户关闭总开关 / 正常停止服务时调用）。 */
    fun cancel(context: Context) {
        val app = context.applicationContext
        cancelNext(app)
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        runCatching { manager?.cancel(restartPendingIntent(app)) }
        lastPlanTarget = 0L
        lastPlanSetAt = 0L
    }

    private fun cancelNext(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { manager.cancel(planPendingIntent(context)) }
        lastPlanTarget = 0L
        lastPlanSetAt = 0L
    }

    /**
     * 设置闹钟：优先精确，无权限/异常时降级。
     * @return 是否设置成功
     */
    private fun setAlarm(
        context: Context,
        manager: AlarmManager,
        triggerAt: Long,
        pending: PendingIntent
    ): Boolean {
        val exact = canScheduleExactAlarms(context)
        if (exact) {
            val ok = runCatching {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        val ok = runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            true
        }.getOrDefault(false)
        if (ok) return true
        // 最后兜底：不保证唤醒，但至少不会崩
        return runCatching {
            manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            true
        }.getOrDefault(false)
    }

    private fun planPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext,
        REQUEST_PLAN,
        Intent(context.applicationContext, AlarmReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun restartPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext,
        REQUEST_RESTART,
        Intent(context.applicationContext, AlarmReceiver::class.java).setAction(ACTION_RESTART),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
