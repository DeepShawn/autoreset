package com.aicode.quotasentry.service

import com.aicode.quotasentry.core.Account
import com.aicode.quotasentry.core.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** [ServiceLabels] 多账号文案与通知 id 的纯逻辑单测。 */
class ServiceLabelsTest {

    private fun account(
        provider: ProviderId = ProviderId.CLAUDE,
        id: String = "account-1",
        label: String = "",
        plan: String = ""
    ) = Account(id = id, provider = provider, label = label, plan = plan)

    @Test
    fun `第一个账号只显示 Provider 名`() {
        assertEquals("Claude", ServiceLabels.accountTitle(account(), index = 0))
        assertEquals("ChatGPT", ServiceLabels.accountTitle(account(provider = ProviderId.CHATGPT), index = 0))
    }

    @Test
    fun `同 Provider 第二个账号带序号`() {
        assertEquals("Claude 2", ServiceLabels.accountTitle(account(id = "a2"), index = 1))
        assertEquals("Claude 3", ServiceLabels.accountTitle(account(id = "a3"), index = 2))
    }

    @Test
    fun `带邮箱时拼成 Provider 加邮箱`() {
        assertEquals(
            "Claude · me@gmail.com",
            ServiceLabels.accountTitle(account(label = "me@gmail.com"), index = 0)
        )
        assertEquals(
            "Claude 2 · me@gmail.com",
            ServiceLabels.accountTitle(account(id = "a2", label = "me@gmail.com"), index = 1)
        )
    }

    @Test
    fun `带计划时拼成 Provider 加计划`() {
        assertEquals("Claude · Pro", ServiceLabels.accountTitle(account(plan = "Pro"), index = 0))
        assertEquals("ChatGPT 2 · Plus", ServiceLabels.accountTitle(account(provider = ProviderId.CHATGPT, id = "g2", plan = "Plus"), index = 1))
    }

    @Test
    fun `同一账号的通知 id 稳定且不同账号不同`() {
        val first = account(id = "stable-1")
        val second = account(id = "stable-2")
        assertEquals(2100 + offset(first), ServiceLabels.notificationId(2100, first))
        assertEquals(ServiceLabels.notificationId(2100, first), ServiceLabels.notificationId(2100, first))
        assertNotEquals(ServiceLabels.notificationId(2100, first), ServiceLabels.notificationId(2100, second))
    }

    @Test
    fun `账号数量文案`() {
        assertEquals("还没有连接的账号", ServiceLabels.accountCountText(0))
        assertEquals("已连接 3 个账号", ServiceLabels.accountCountText(3))
    }

    private fun offset(account: Account): Int = (account.id.hashCode() and 0x7FFFFFFF) % 900
}
