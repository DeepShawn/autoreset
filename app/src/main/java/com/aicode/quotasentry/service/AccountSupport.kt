package com.aicode.quotasentry.service

import com.aicode.quotasentry.core.Account
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.Store

/**
 * 服务层内部的多账号查询封装。
 *
 * 注意：这里所有方法都直接读 [Store] 的最新快照（同进程 StateFlow，无 IO），
 * 不要在长循环里缓存 account 列表，UI 可能随时增删账号。
 */
internal object AccountSupport {

    /** 所有已连接账号（按 Provider 分组、连接时间排序）。 */
    fun linked(): List<Account> = Store.linkedAccounts()

    /** 某 Provider 下所有已连接账号（计划到点时对它们逐个发送）。 */
    fun linkedFor(provider: ProviderId): List<Account> =
        Store.linkedAccounts().filter { it.provider == provider }

    fun hasLinked(): Boolean = Store.linkedAccounts().isNotEmpty()

    /** 账号可读标题（自动计算同 Provider 内的序号）。 */
    fun title(account: Account): String {
        val siblings = Store.accountsFor(account.provider)
        val index = siblings.indexOfFirst { it.id == account.id }.takeIf { it >= 0 } ?: 0
        return ServiceLabels.accountTitle(account, index)
    }

    /**
     * 某 Provider 下最近一次「仍然有效」的官方窗口重置时刻，用于：
     * - FOLLOW_RESET 计划的下次触发锚点；
     * - AlarmManager 兜底唤醒时刻。
     *
     * 多账号时取最早的那个（谁先重置就先按谁安排一次发送）。
     */
    fun nextResetAt(provider: ProviderId, now: Long, windowMillis: Long): Long? {
        val guard = now - windowMillis.coerceAtLeast(1L) / 2
        return Store.accountsFor(provider)
            .mapNotNull { account ->
                Store.quotaFor(account).officialResetAt?.takeIf { it > guard }
            }
            .minOrNull()
    }
}
