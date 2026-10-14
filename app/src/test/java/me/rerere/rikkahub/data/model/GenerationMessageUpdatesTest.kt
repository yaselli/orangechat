package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.data.service.registerProactiveRunMessageId
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GenerationMessageUpdatesTest {
    @Test
    fun `stream updates one reply without adding nodes or changing node identity`() {
        val user = UIMessage.user("question")
        val reply = UIMessage.assistant("first")
        val initial = conversation(MessageNode.of(user))
        val first = initial.updateGenerationMessages(listOf(user, reply))
        val completed = reply.copy(parts = listOf(UIMessagePart.Text("first and final")))
        val second = first.updateGenerationMessages(listOf(user, completed))
        assertEquals(2, second.messageNodes.size)
        assertEquals(first.messageNodes.map { it.id }, second.messageNodes.map { it.id })
        assertEquals(completed, second.currentMessages.last())
        assertEquals(initial.messageNodes.single(), second.messageNodes.first())
    }

    @Test
    fun `stale request positions cannot put assistant into a concurrently appended user node`() {
        val user = UIMessage.user("first")
        val nextUser = UIMessage.user("next")
        val reply = UIMessage.assistant("reply")
        val current = conversation(MessageNode.of(user), MessageNode.of(nextUser))
        val updated = current.updateGenerationMessages(listOf(user, reply))
        assertEquals(current.messageNodes, updated.messageNodes.take(2))
        assertEquals(listOf(user, nextUser, reply), updated.currentMessages)
        assertEquals(1, updated.messageNodes[1].messages.size)
    }

    @Test
    fun `message id finds its node after other nodes were inserted before it`() {
        val reply = UIMessage.assistant("partial")
        val node = MessageNode.of(reply)
        val inserted = MessageNode.of(UIMessage.user("inserted"))
        val current = conversation(inserted, node)
        val complete = reply.copy(parts = listOf(UIMessagePart.Text("complete")))
        val updated = current.updateGenerationMessages(listOf(complete))
        assertEquals(inserted, updated.messageNodes.first())
        assertEquals(node.id, updated.messageNodes[1].id)
        assertEquals(complete, updated.messageNodes[1].currentMessage)
        assertEquals(2, updated.messageNodes.size)
    }

    @Test
    fun `updating inactive alternative preserves selected reply and favorite flag`() {
        val old = UIMessage.assistant("old")
        val selected = UIMessage.assistant("selected")
        val node = MessageNode(messages = listOf(old, selected), selectIndex = 1, isFavorite = true)
        val current = conversation(node)
        val updated = current.updateGenerationMessages(listOf(old.copy(parts = listOf(UIMessagePart.Text("updated")))))
        assertEquals(node.id, updated.messageNodes.single().id)
        assertEquals(1, updated.messageNodes.single().selectIndex)
        assertTrue(updated.messageNodes.single().isFavorite)
        assertEquals(selected, updated.currentMessages.single())
        assertEquals("updated", updated.messageNodes.single().messages.first().toText())
    }

    @Test
    fun `regeneration adds one selected alternative then streams into the same id`() {
        val old = UIMessage.assistant("old")
        val node = MessageNode.of(old).copy(isFavorite = true)
        val reply = UIMessage.assistant("new")
        val target = GenerationMessageTarget(reply.id, node.id)
        val first = conversation(node).updateGenerationMessages(listOf(reply), target)
        val complete = reply.copy(parts = listOf(UIMessagePart.Text("new final")))
        val second = first.updateGenerationMessages(listOf(complete), target)
        assertEquals(1, second.messageNodes.size)
        assertEquals(node.id, second.messageNodes.single().id)
        assertEquals(listOf(old, complete), second.messageNodes.single().messages)
        assertEquals(1, second.messageNodes.single().selectIndex)
        assertTrue(second.messageNodes.single().isFavorite)
    }

    @Test
    fun `regeneration target follows node id and never absorbs a different new message`() {
        val node = MessageNode.of(UIMessage.assistant("old"))
        val inserted = MessageNode.of(UIMessage.user("inserted"))
        val following = MessageNode.of(UIMessage.user("following"))
        val reply = UIMessage.assistant("regenerated")
        val other = UIMessage.assistant("another turn")
        val updated = conversation(inserted, node, following).updateGenerationMessages(
            listOf(reply, other), GenerationMessageTarget(reply.id, node.id),
        )
        assertEquals(inserted, updated.messageNodes[0])
        assertEquals(node.id, updated.messageNodes[1].id)
        assertEquals(reply, updated.messageNodes[1].currentMessage)
        assertEquals(following, updated.messageNodes[2])
        assertEquals(other, updated.messageNodes[3].currentMessage)
    }

    @Test
    fun `missing regeneration target does not redirect reply into another node`() {
        val node = MessageNode.of(UIMessage.user("keep"))
        val reply = UIMessage.assistant("new")
        val updated = conversation(node).updateGenerationMessages(
            listOf(reply), GenerationMessageTarget(reply.id, Uuid.random()),
        )
        assertEquals(listOf(node), updated.messageNodes)
    }

    @Test
    fun `reasoning tool state and outputs survive the update unchanged`() {
        val reply = UIMessage.assistant("partial")
        val tool = UIMessagePart.Tool("call", "example", "{}", approvalState = ToolApprovalState.Approved)
        val completed = reply.copy(parts = listOf(
            UIMessagePart.Reasoning("thinking"),
            tool.copy(output = listOf(UIMessagePart.Text("result"))),
            UIMessagePart.Text("final"),
        ))
        val updated = conversation(MessageNode.of(reply)).updateGenerationMessages(listOf(completed))
        assertEquals(completed, updated.currentMessages.single())
        assertEquals(ToolApprovalState.Approved, updated.currentMessages.single().getTools().single().approvalState)
    }

    @Test
    fun `conversation metadata and empty updates preserve history`() {
        val current = conversation(MessageNode.of(UIMessage.user("keep"))).copy(
            title = "title", chatSuggestions = listOf("suggestion"), isPinned = true,
            customSystemPrompt = "prompt", workspaceCwd = "/workspace", folderId = Uuid.random(),
        )
        assertEquals(current, current.updateGenerationMessages(emptyList()))
        val updated = current.updateGenerationMessages(listOf(UIMessage.assistant("new")))
        assertEquals(current, updated.copy(messageNodes = current.messageNodes))
    }

    @Test
    fun `proactive ownership still rejects a preexisting message before the common update`() {
        val old = UIMessage.assistant("old")
        val fresh = UIMessage.assistant("new")
        val protected = setOf(old.id)
        val owned = mutableSetOf<Uuid>()
        assertFalse(registerProactiveRunMessageId(old.id, protected, owned))
        assertTrue(registerProactiveRunMessageId(fresh.id, protected, owned))
        val current = conversation(MessageNode.of(old))
        val updated = current.updateGenerationMessages(listOf(fresh))
        assertEquals(old, updated.currentMessages.first())
        assertEquals(fresh, updated.currentMessages.last())
        assertEquals(setOf(fresh.id), owned)
    }

    @Test
    fun `deleted history is not restored by a stale generation snapshot`() {
        val deleted = UIMessage.user("deleted")
        val kept = UIMessage.user("keep")
        val newUser = UIMessage.user("new concurrent message")
        val fresh = UIMessage.assistant("reply")
        val current = conversation(MessageNode.of(kept), MessageNode.of(newUser))
        val updated = current.updateGenerationMessages(
            messages = listOf(deleted, kept, fresh),
            previouslySeenMessageIds = setOf(deleted.id, kept.id),
        )
        assertEquals(listOf(kept, newUser, fresh), updated.currentMessages)
        assertEquals(current.messageNodes, updated.messageNodes.take(2))
    }

    @Test
    fun `deleted streaming reply is not reappended by later frames`() {
        val user = UIMessage.user("question")
        val reply = UIMessage.assistant("partial")
        val initial = conversation(MessageNode.of(user))
        val published = initial.updateGenerationMessages(listOf(user, reply), previouslySeenMessageIds = setOf(user.id))
        val afterDelete = published.copy(messageNodes = published.messageNodes.dropLast(1))
        val complete = reply.copy(parts = listOf(UIMessagePart.Text("final")))
        val updated = afterDelete.updateGenerationMessages(
            listOf(user, complete), previouslySeenMessageIds = setOf(user.id, reply.id),
        )
        assertEquals(afterDelete, updated)
    }

    @Test
    fun `deleted alternative stays deleted while another selected reply survives`() {
        val deleted = UIMessage.assistant("old alternative")
        val selected = UIMessage.assistant("selected")
        val node = MessageNode(messages = listOf(selected), isFavorite = true)
        val current = conversation(node)
        val updated = current.updateGenerationMessages(
            listOf(deleted), previouslySeenMessageIds = setOf(deleted.id, selected.id),
        )
        assertEquals(current, updated)
        assertTrue(updated.messageNodes.single().isFavorite)
    }

    @Test
    fun `deleting regeneration node after the first frame prevents later reply resurrection`() {
        val original = MessageNode.of(UIMessage.assistant("old"))
        val following = MessageNode.of(UIMessage.user("following"))
        val reply = UIMessage.assistant("new")
        val target = GenerationMessageTarget(reply.id, original.id)
        val first = conversation(original, following).updateGenerationMessages(listOf(reply), target)
        val afterDelete = first.copy(messageNodes = first.messageNodes.filterNot { it.id == original.id })
        val later = reply.copy(parts = listOf(UIMessagePart.Text("new final")))
        val updated = afterDelete.updateGenerationMessages(
            listOf(later), target, previouslySeenMessageIds = setOf(reply.id),
        )
        assertEquals(afterDelete, updated)
        assertEquals(following, updated.messageNodes.single())
    }

    @Test
    fun `seen replies still update normally when their node exists`() {
        val reply = UIMessage.assistant("partial")
        val current = conversation(MessageNode.of(reply))
        val later = reply.copy(parts = listOf(UIMessagePart.Text("final")))
        val updated = current.updateGenerationMessages(listOf(later), previouslySeenMessageIds = setOf(reply.id))
        assertEquals(current.messageNodes.single().id, updated.messageNodes.single().id)
        assertEquals(later, updated.currentMessages.single())
        assertEquals(1, updated.messageNodes.size)
    }

    @Test
    fun `proactive registration permits first publication but not resurrection after deletion`() {
        val old = UIMessage.assistant("old")
        val fresh = UIMessage.assistant("partial proactive reply")
        val protected = setOf(old.id)
        val owned = mutableSetOf<Uuid>()
        val beforeFirst = owned.toSet()
        assertTrue(registerProactiveRunMessageId(fresh.id, protected, owned))
        val first = conversation(MessageNode.of(old)).updateGenerationMessages(
            listOf(fresh), previouslySeenMessageIds = beforeFirst,
        )
        assertEquals(listOf(old, fresh), first.currentMessages)
        val afterDelete = first.copy(messageNodes = first.messageNodes.dropLast(1))
        val beforeLater = owned.toSet()
        assertTrue(registerProactiveRunMessageId(fresh.id, protected, owned))
        val updated = afterDelete.updateGenerationMessages(
            listOf(fresh.copy(parts = listOf(UIMessagePart.Text("final")))),
            previouslySeenMessageIds = beforeLater,
        )
        assertEquals(afterDelete, updated)
        assertFalse(registerProactiveRunMessageId(old.id, protected, owned))
    }

    private fun conversation(vararg nodes: MessageNode) = Conversation(
        assistantId = Uuid.random(), messageNodes = nodes.toList(),
    )
}
