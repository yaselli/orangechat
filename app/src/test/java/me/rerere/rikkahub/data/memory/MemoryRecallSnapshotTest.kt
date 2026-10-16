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
    @Test
    fun `history snapshot revalidates current selected source hash role and assistant scope`() {
        val original = HistoryMemorySource("past", "node", "old-message", "assistant-a", "user",
            "2020-01-01T00:00", "喜欢燕麦牛奶", 0)
        val saved = SavedMemoryExcerpt(0, 0, original.hash, "燕麦牛奶", original.reference)
        val boundary = HistoryRecallBoundary("chat", 0, "2026-01-01T00:00", emptySet())
        fun render(source: HistoryMemorySource?, scope: String = "assistant-a") = validMemorySnapshot(
            listOf(saved), emptyMap(), "__global__",
            source?.let { mapOf(it.reference.key to it) }.orEmpty(), scope, boundary,
        )
        assertTrue(render(original).contains("[history user"))
        assertEquals("", render(null))
        assertEquals("", render(original.copy(text = "现在不喜欢了")))
        assertEquals("", render(original.copy(role = "assistant")))
        assertEquals("", render(original.copy(messageId = "other-branch")))
        assertEquals("", render(original, "assistant-b"))
    }

    @Test
    fun `history is request only and cannot be copied into a later user turn`() {
        val original = HistoryMemorySource("past", "node", "old-message", "assistant-a", "assistant",
            "2020-01-01T00:00", "喜欢燕麦牛奶", 0)
        val user = UIMessage.user("喜欢喝什么")
        val turn = MemoryRecallTurn("__global__", user.id.toString(),
            listOf(SavedMemoryExcerpt(0, 0, original.hash, original.text, original.reference)), "assistant-a",
            HistoryRecallBoundary("chat", 0, "2026-01-01T00:00", setOf(user.id.toString())))
        val sourceMap = mapOf(original.reference.key to original)
        val request = turn.apply(listOf(user), emptyMap(), sourceMap)
        assertEquals(2, request.single().parts.size)
        assertEquals(1, user.parts.size)
        assertEquals(request, turn.apply(listOf(user), emptyMap(), sourceMap))
        val later = listOf(user, UIMessage.assistant("好的"), UIMessage.user("另一轮"))
        assertEquals(later, turn.apply(later, emptyMap(), sourceMap))
    }

    @Test
    fun `query embedding excludes marked time background while retaining ordinary visible text`() {
        val user = UIMessage.user("燕麦牛奶").copy(parts = listOf(
            UIMessagePart.Text("燕麦牛奶"),
            me.rerere.rikkahub.data.ai.transformers.extraInfoMessagePart("hidden clock and phone background"),
        ))
        assertEquals("燕麦牛奶", memoryRecallQuery(listOf(user), user))
    }

}
