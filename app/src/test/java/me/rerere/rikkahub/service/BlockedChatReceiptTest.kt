package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class BlockedChatReceiptTest {
    private val finished = LocalDateTime(2026, 9, 21, 12, 0)

    @Test
    fun `streamed reply gets a new id and receipt never enters stored history`() {
        val user = UIMessage.user("我先不说话了")
        val previous = UIMessage.assistant("好").copy(finishedAt = finished)
        val receipt = blockedChatReceipt()
        var wire = listOf(user, previous, receipt)
        var conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(user.toMessageNode(), previous.toMessageNode()),
        )
        for (part in listOf("我", "还在")) {
            wire = wire.handleMessageChunk(MessageChunk(
                id = "chunk",
                model = "fake",
                choices = listOf(UIMessageChoice(0, UIMessage.assistant(part), null, null)),
            ))
            conversation = applyBlockedChatChunk(conversation, wire, receipt.id)
        }
        assertEquals(3, conversation.messageNodes.size)
        assertEquals(listOf(user, previous), conversation.currentMessages.take(2))
        assertNotEquals(previous.id, conversation.currentMessages.last().id)
        assertEquals("我还在", conversation.currentMessages.last().toText())
        assertEquals(1, conversation.currentMessages.count { it.role == MessageRole.USER })
        assertFalse(conversation.messageNodes.flatMap { it.messages }.any { it.id == receipt.id })
    }

    @Test
    fun `application receipt bypasses user transformers and stays before tool turn`() = runBlocking {
        val user = UIMessage.user("原始消息")
        val receipt = blockedChatReceipt()
        val tool = UIMessagePart.Tool("call_1", "fake_tool", "{}", output = listOf(UIMessagePart.Text("ok")))
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val result = transformBlockedChatRequest(listOf(user, receipt, assistant), receipt, setOf(user.id)) {
            assertEquals(listOf(user, assistant), it)
            it
        }
        assertEquals(listOf(user, receipt, assistant), result)
        assertEquals(tool, result.last().getTools().single())
    }

    @Test
    fun `short context restores receipt before current tools even after truncation`() = runBlocking {
        val receipt = blockedChatReceipt()
        val assistant = UIMessage.assistant("正在处理")
        val result = transformBlockedChatRequest(listOf(assistant), receipt, emptySet()) { it }
        assertEquals(listOf(receipt, assistant), result)
    }

    @Test
    fun `empty stream previous reply skip and pending approval do not count`() {
        val previous = UIMessage.assistant("旧回复").copy(finishedAt = finished)
        val fresh = UIMessage.assistant("新回复").copy(finishedAt = finished)
        val pending = UIMessagePart.Tool("call_1", "fake", "{}", approvalState = ToolApprovalState.Pending)
        assertFalse(isCompletedBlockedReply(previous, previous.id))
        assertFalse(isCompletedBlockedReply(null, previous.id))
        assertFalse(isCompletedBlockedReply(UIMessage.assistant("半截"), previous.id))
        assertFalse(isCompletedBlockedReply(UIMessage.assistant("[SKIP]").copy(finishedAt = finished), previous.id))
        assertFalse(isCompletedBlockedReply(fresh.copy(parts = fresh.parts + pending), previous.id))
        assertTrue(isCompletedBlockedReply(fresh, previous.id))
    }
}
