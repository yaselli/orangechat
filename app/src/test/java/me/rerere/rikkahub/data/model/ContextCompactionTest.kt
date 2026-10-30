package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ContextCompactionTest {
    @Test
    fun `cursor stays small even when thousands of messages are covered`() {
        val messages = (1..2000).flatMap { listOf(UIMessage.user("question $it"), UIMessage.assistant("answer $it")) }
        val original = Conversation(assistantId = Uuid.random(), messageNodes = messages.map { MessageNode.of(it) })
        val result = original.planCompaction(0).result("summary")
        assertEquals(4000, result.coveredMessageCount)
        assertEquals(64, result.coveredPrefixHash.length)
        assertTrue(JsonInstant.encodeToString(result).length < 300)
        assertTrue(result.matches(original.currentMessages))
    }

    @Test
    fun `local recall records do not change existing summary fingerprints or default serialized history`() {
        val original = conversation()
        val summary = original.planCompaction(2).result("summary")
        assertFalse(JsonInstant.encodeToString(original.currentMessages[0]).contains("localMetadata"))
        val annotated = original.currentMessages.map { message ->
            message.copy(localMetadata = buildJsonObject { put("memory_recall_v1", "UI-only") })
        }
        assertTrue(summary.matches(annotated))
        assertEquals("summary", selectCompactedContext(annotated, summary).summary)
    }

    private fun conversation() = Conversation(
        assistantId = Uuid.random(),
        messageNodes = listOf(
            MessageNode.of(UIMessage.user("first")),
            MessageNode(messages = listOf(UIMessage.assistant("alternative"), UIMessage.assistant("selected")),
                selectIndex = 1, isFavorite = true),
            MessageNode.of(UIMessage.user("recent")),
            MessageNode.of(UIMessage.assistant("recent reply")),
        ),
    )

    @Test
    fun `compacting preserves every original node branch attachment and favorite`() {
        val original = conversation()
        val plan = original.planCompaction(2)
        val compacted = original.copy(contextCompaction = plan.result("earlier facts"))
        assertSame(original.messageNodes, compacted.messageNodes)
        assertEquals(original.currentMessages.takeLast(2),
            selectCompactedContext(compacted.currentMessages, compacted.contextCompaction).messages)
        assertEquals("earlier facts", selectCompactedContext(compacted.currentMessages, compacted.contextCompaction).summary)
        assertEquals(2, compacted.messageNodes[1].messages.size)
        assertTrue(compacted.messageNodes[1].isFavorite)
    }

    @Test
    fun `append keeps summary valid and second compact uses old summary plus only new turns`() {
        val original = conversation()
        val first = original.copy(contextCompaction = original.planCompaction(2).result("old summary"))
        val later = first.copy(messageNodes = first.messageNodes +
            listOf(MessageNode.of(UIMessage.user("new")), MessageNode.of(UIMessage.assistant("new answer"))))
        val plan = later.planCompaction(2)
        assertEquals("old summary", plan.previousSummary)
        assertEquals(original.currentMessages.takeLast(2), plan.newMessages)
        val second = later.copy(contextCompaction = plan.result("merged summary"))
        assertEquals(later.currentMessages.takeLast(2),
            selectCompactedContext(second.currentMessages, second.contextCompaction).messages)
    }

    @Test
    fun `edited selected content branch deletion reorder and truncated regeneration invalidate summary`() {
        val original = conversation()
        val summary = original.planCompaction(2).result("summary")
        val source = original.currentMessages
        val modified = listOf(
            source.mapIndexed { i, m -> if (i == 0) m.copy(parts = listOf(UIMessagePart.Text("edited"))) else m },
            source.toMutableList().apply { this[1] = original.messageNodes[1].messages.first() },
            source.drop(1),
            source.reversed(),
            source.take(1),
        )
        for (messages in modified) {
            val selected = selectCompactedContext(messages, summary)
            assertNull(selected.summary)
            assertSame(messages, selected.messages)
        }
    }

    @Test
    fun `editing an uncovered recent message does not invalidate earlier summary`() {
        val original = conversation()
        val summary = original.planCompaction(2).result("summary")
        val messages = original.currentMessages.toMutableList().apply { this[3] = UIMessage.assistant("edited recent") }
        assertTrue(summary.matches(messages))
    }

    @Test
    fun `cut moves back to turn boundary and never leaves an assistant continuation alone`() {
        val original = conversation()
        val plan = original.planCompaction(1)
        assertEquals(original.currentMessages.take(2), plan.newMessages)
        assertEquals(original.currentMessages[2].role,
            selectCompactedContext(original.currentMessages, plan.result("summary")).messages.first().role)
    }

    @Test
    fun `pending tool blocks compact while executed result remains available for summary`() {
        val original = conversation()
        val tool = UIMessagePart.Tool("call", "lookup", "{}")
        val messages = original.messageNodes.toMutableList().apply {
            this[1] = MessageNode.of(UIMessage.assistant("").copy(parts = listOf(tool)))
        }
        val pending = original.copy(messageNodes = messages)
        assertThrows(IllegalArgumentException::class.java) { pending.planCompaction(0) }
        messages[1] = MessageNode.of(UIMessage.assistant("").copy(parts = listOf(
            tool.copy(output = listOf(UIMessagePart.Text("result")))
        )))
        assertEquals(4, original.copy(messageNodes = messages).planCompaction(0).newMessages.size)
    }

    @Test
    fun `old exports decode without summary and new summary round trips`() {
        val original = conversation()
        val oldJson = JsonInstant.encodeToString(original).replace(",\"contextCompaction\":null", "")
        assertNull(JsonInstant.decodeFromString<Conversation>(oldJson).contextCompaction)
        val compacted = original.copy(contextCompaction = original.planCompaction(2).result("summary"))
        val restored = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(compacted))
        assertEquals(compacted.contextCompaction, restored.contextCompaction)
        assertTrue(requireNotNull(restored.contextCompaction).matches(restored.currentMessages))
    }

    @Test
    fun `invalid summary restarts from original history instead of carrying stale facts`() {
        val original = conversation()
        val first = original.copy(contextCompaction = original.planCompaction(2).result("outdated"))
        val changed = first.copy(messageNodes = listOf(MessageNode.of(UIMessage.user("changed"))) +
            first.messageNodes.drop(1))
        val plan = changed.planCompaction(2)
        assertNull(plan.previousSummary)
        assertEquals(changed.currentMessages.take(2), plan.newMessages)
        assertEquals(first.contextCompaction, plan.previous)
    }

    @Test
    fun `no new history or blank model output cannot create a new cursor`() {
        val original = conversation()
        val plan = original.planCompaction(0)
        assertThrows(IllegalArgumentException::class.java) { plan.result("  ") }
        val compacted = original.copy(contextCompaction = plan.result("all history"))
        assertThrows(IllegalArgumentException::class.java) { compacted.planCompaction(0) }
    }
}
