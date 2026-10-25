package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveProviderRequestTest {
    @Test
    fun `Gemini relay request ends with background event while real history stays unchanged`() {
        val user = UIMessage.user("我先忙一下")
        val assistant = reply("好，等你")
        val history = listOf(UIMessage.system("后台判定规则"), user, assistant)

        val request = ProactiveProviderRequest.create(history, "google/gemini-3.8-flash", false)!!
        val result = request.applyTo(history)

        assertEquals(history, result.dropLast(1))
        assertSame(assistant, history.last())
        assertEquals(3, history.size)
        assertEquals(MessageRole.USER, result.last().role)
        val eventText = result.last().parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(eventText.contains("系统后台事件"))
        assertTrue(eventText.contains("不是对方的新发言"))
        assertEquals(listOf(user), history.filter { it.role == MessageRole.USER })
    }

    @Test
    fun `background boundary survives tool rounds without interrupting function results`() {
        val previousReply = reply("回头见")
        val history = listOf(UIMessage.user("我出去一下"), previousReply)
        val request = ProactiveProviderRequest.create(history, "gemini-3.8-flash", false)!!
        val toolTurn = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(
                toolCallId = "call_now",
                toolName = "get_time_info",
                input = "{}",
                output = listOf(UIMessagePart.Text("18:00")),
            )),
        )

        val firstRequest = request.applyTo(history)
        val secondRequest = mergeAdjacentSameRoleMessages(request.applyTo(history + toolTurn))

        assertEquals(listOf(previousReply, firstRequest.last(), toolTurn), secondRequest.takeLast(3))
        assertSame(toolTurn, secondRequest.last())
        assertEquals("call_now", secondRequest.last().getTools().single().toolCallId)
        assertTrue(secondRequest.last().getTools().single().isExecuted)
        assertEquals(2, secondRequest.count { it.role == MessageRole.USER })
        assertSame(firstRequest, request.applyTo(firstRequest))
    }

    @Test
    fun `other providers retain their original request format`() {
        val history = listOf(UIMessage.user("你好"), reply("你好呀"))
        listOf("kimi-k3", "deepseek-chat", "gpt-5", "claude-sonnet").forEach { modelId ->
            assertNull(ProactiveProviderRequest.create(history, modelId, false))
        }
    }

    @Test
    fun `native Google aliases also receive the compatibility event`() {
        val history = listOf(reply("稍后见"))
        assertNotNull(ProactiveProviderRequest.create(history, "custom-model-alias", true))
        assertNotNull(ProactiveProviderRequest.create(history, "Gemini-3.8-Flash", false))
    }

    @Test
    fun `a real user ending or empty history does not receive another user turn`() {
        assertNull(ProactiveProviderRequest.create(listOf(UIMessage.user("在吗")), "gemini-3.8-flash", false))
        assertNull(ProactiveProviderRequest.create(emptyList(), "gemini-3.8-flash", true))
    }

    @Test
    fun `missing boundary cannot append an event after an unrelated tool turn`() {
        val history = listOf(reply("以前的回复"))
        val request = ProactiveProviderRequest.create(history, "gemini-3.8-flash", false)!!
        val differentHistory = listOf(reply("另一段上下文"))
        assertSame(differentHistory, request.applyTo(differentHistory))
    }

    private fun reply(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )
}
