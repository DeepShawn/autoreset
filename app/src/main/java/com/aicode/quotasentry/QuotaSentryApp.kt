package com.aicode.quotasentry

import android.app.Application
import com.aicode.quotasentry.core.Store
import com.aicode.quotasentry.service.NotificationHelper
import com.aicode.quotasentry.service.ServiceController

class QuotaSentryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 全应用唯一的数据仓库；必须早于任何界面/服务使用。
        Store.init(this)
        // 渠道尽早建好：服务被系统拒绝启动时，通知兜底也不会被丢弃。
        runCatching { NotificationHelper.ensureChannels(this) }

        // 用户上次开过「后台常驻」，且至少连接过一个账号时，冷启动顺手把服务拉起来。
        // 这里只做 best-effort：Android 12+ 在后台启动前台服务可能被拒，失败不影响使用。
        val settings = Store.settingsNow()
        val linked = Store.accounts.value.values.any { it.linked }
        if (settings.backgroundEnabled && linked) {
            runCatching { ServiceController.start(this) }
        }
    }
}
