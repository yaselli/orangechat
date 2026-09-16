/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveMessageHistoryTest {
    @Test
    fun `unrecoverable tool call is removed but surrounding history is preserved`() {
        val user = UIMessage.user("帮我改一下")
        val orphanedToolMessage = assistantWithTool(
            toolCallId = "call_orphaned",
            approvalState = ToolApprovalState.Auto,
        )
        val laterUser = UIMessage.user("还在吗")

        val result = filterInvalidToolMessages(listOf(user, orphanedToolMessage, laterUser))

        assertEquals(listOf(user, laterUser), result)
    }

    @Test
    fun `executed failed tool result remains paired with its call id`() {
        val failedToolMessage = assistantWithTool(
            toolCallId = "call_failed",
            output = listOf(UIMessagePart.Text("{\"error\":\"Accessibility failed\"}")),
        )

        val result = filterInvalidToolMessages(listOf(failedToolMessage))

        assertEquals(1, result.size)
        val tool = result.single().getTools().single()
        assertEquals("call_failed", tool.toolCallId)
        assertTrue(tool.isExecuted)
    }

    @Test
    fun `approved denied and answered tools remain available for resume`() {
        val states = listOf(
            ToolApprovalState.Approved,
            ToolApprovalState.Denied("not now"),
            ToolApprovalState.Answered("yes"),
        )

        states.forEachIndexed { index, state ->
            val message = assistantWithTool("call_$index", approvalState = state)
            assertEquals(listOf(message), filterInvalidToolMessages(listOf(message)))
        }
    }

    @Test
    fun `adjacent roles merge without changing the first message identity`() {
        val firstAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("第一段")),
        )
        val secondAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("第二段")),
        )
        val user = UIMessage.user("继续")

        val result = mergeAdjacentSameRoleMessages(listOf(firstAssistant, secondAssistant, user))

        assertEquals(2, result.size)
        assertEquals(firstAssistant.id, result.first().id)
        assertEquals(
            listOf(UIMessagePart.Text("第一段"), UIMessagePart.Text("第二段")),
            result.first().parts,
        )
        assertEquals(user, result.last())
    }

    private fun assistantWithTool(
        toolCallId: String,
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
        output: List<UIMessagePart> = emptyList(),
    ) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = toolCallId,
                toolName = "accessibility",
                input = "{}",
                output = output,
                approvalState = approvalState,
            ),
        ),
    )
}
