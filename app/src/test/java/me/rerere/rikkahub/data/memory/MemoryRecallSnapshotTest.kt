package me.rerere.rikkahub.data.memory

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import org.junit.Assert.*
import org.junit.Test

class MemoryRecallSnapshotTest {
    private val source = MemoryEntity(id = 7, assistantId = "a", content = "喜欢炸鸡因为脆脆的", revision = 2)
    private val excerpt = SavedMemoryExcerpt(7, 2, memoryHash(source.content), "喜欢炸鸡")

    @Test
    fun `request copies preserve source history and keep recall only in the active turn`() {
        val first = UIMessage.user("喜欢吃什么")
        val turn = MemoryRecallTurn("a", first.id.toString(), listOf(excerpt))
        val original = listOf(first)
        val request = turn.apply(original, mapOf(7 to source))
        val later = turn.apply(original + UIMessage.assistant("炸鸡") + UIMessage.user("喝什么"), mapOf(7 to source))
        assertEquals(first, later.first())
        assertEquals(1, later.first().parts.size)
        assertEquals(1, original.first().parts.size)
        assertEquals(2, request.first().parts.size)
        assertEquals(first, original.first())
        assertTrue((request.first().parts.last() as UIMessagePart.Text).text.contains("[memory #7]"))
    }

    @Test
    fun `deletion correction scope change and restored mismatched content remove old references`() {
        assertEquals("", validMemorySnapshot(listOf(excerpt), emptyMap(), "a"))
        assertEquals("", validMemorySnapshot(listOf(excerpt), mapOf(7 to source.copy(revision = 3)), "a"))
        assertEquals("", validMemorySnapshot(listOf(excerpt), mapOf(7 to source), "b"))
        assertEquals("", validMemorySnapshot(listOf(excerpt), mapOf(7 to source.copy(content = "不喜欢了")), "a"))
        assertEquals("", validMemorySnapshot(listOf(excerpt.copy(text = "伪造原文")), mapOf(7 to source), "a"))
    }

    @Test
    fun `restored oversized snapshots cannot bypass the prompt budget`() {
        val huge = source.copy(content = "a".repeat(4000))
        val saved = SavedMemoryExcerpt(7, 2, memoryHash(huge.content), huge.content)
        assertEquals("", validMemorySnapshot(listOf(saved), mapOf(7 to huge), "a"))
        assertEquals("", validMemorySnapshot(List(7) { excerpt }, mapOf(7 to source), "a"))
    }

    @Test
    fun `visible text determines queries and reasoning never leaks to embedding service`() {
        val old = UIMessage.user("炸鸡")
        val answer = UIMessage.assistant("脆脆的").copy(parts = listOf(
            UIMessagePart.Text("脆脆的"), UIMessagePart.Reasoning(reasoning = "private reasoning"),
        ))
        val current = UIMessage.user("这个呢")
        val query = memoryRecallQuery(listOf(old, answer, current), current)
        assertTrue(query.startsWith("这个呢"))
        assertTrue(query.contains("炸鸡"))
        assertFalse(query.contains("private reasoning"))
    }

    @Test
    fun `message hash ignores usage and translation but changes with branch text or attachments`() {
        val message = UIMessage.user("原文")
        assertEquals(memoryMessageHash(message), memoryMessageHash(message.copy(translation = "translated")))
        assertNotEquals(memoryMessageHash(message), memoryMessageHash(
            message.copy(parts = listOf(UIMessagePart.Text("改了")))
        ))
    }
}
