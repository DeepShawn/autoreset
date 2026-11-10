package com.aicode.quotasentry.service

import com.aicode.quotasentry.core.Account

/**
 * 多账号场景下的纯文案 / 通知 id 计算，不依赖 Android 与 Store，便于单元测试。
 *
 * 同一个 Provider 可以连接多个账号，所有事件、通知、日志都必须带上账号名，
 * 否则用户无法区分是哪一个账号发送/失败。
 */
object ServiceLabels {

    /**
     * 账号可读标题，例如：
     * - 只有账号（第一个）：`Claude`
     * - 同 Provider 第二个账号：`Claude 2`
     * - 带邮箱：`Claude · me@gmail.com`
     * - 带计划且是第二个账号：`Claude 2 · Pro`
     */
    fun accountTitle(account: Account, index: Int = 0): String {
        val order = if (index > 0) {
            "${account.provider.label} ${index + 1}"
        } else {
            account.provider.label
        }
        val suffix = account.label.ifBlank { account.plan }
        return if (suffix.isBlank()) order else "$order · $suffix"
    }

    /**
     * 账号维度的通知 id：同一账号固定复用（新结果覆盖旧结果），不同账号互不覆盖。
     *
     * 用账号 id 的哈希取 900 个槽位，理论上可能撞车，但只影响旧通知被覆盖，不会丢功能。
     */
    fun notificationId(base: Int, account: Account): Int {
        val slot = (account.id.hashCode() and 0x7FFFFFFF) % 900
        return base + slot
    }

    /** 常驻通知里的账号数量文案。 */
    fun accountCountText(count: Int): String =
        if (count <= 0) "还没有连接的账号" else "已连接 $count 个账号"
}
