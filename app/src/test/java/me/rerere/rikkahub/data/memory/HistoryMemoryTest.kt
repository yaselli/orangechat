package me.rerere.rikkahub.data.memory

import org.junit.Assert.*
import org.junit.Test

class HistoryMemoryTest {
    private val source = HistoryMemorySource("conversation-a", "node", "message", "assistant-a", "user",
        "2026-01-01T12:00", "喜欢牛奶，因为喝起来很香", 0)

    @Test
    fun `history slice identity separates forked reused message IDs scopes and edited text`() {
        val id = historyMemorySlices(source).single().id
        assertNotEquals(id, historyMemorySlices(source.copy(conversationId = "fork")).single().id)
        assertNotEquals(id, historyMemorySlices(source.copy(scope = "assistant-b")).single().id)
        assertNotEquals(id, historyMemorySlices(source.copy(text = "现在不喜欢了")).single().id)
        assertNotEquals(source.hash, source.copy(role = "assistant").hash)
        assertNotEquals(source.hash, source.copy(createdAt = "2026-01-02T12:00").hash)
    }

    @Test
    fun `history respects visible message IDs regeneration boundary and future dates`() {
        val boundary = HistoryRecallBoundary("conversation-a", 2, "2026-01-02T00:00", setOf("visible"))
        assertTrue(boundary.allows(source))
        assertFalse(boundary.allows(source.copy(nodeIndex = 2)))
        assertFalse(boundary.allows(source.copy(nodeIndex = 3)))
        assertFalse(boundary.allows(source.copy(messageId = "visible")))
        assertFalse(boundary.allows(source.copy(conversationId = "other", createdAt = "2026-01-03T00:00")))
        assertTrue(boundary.allows(source.copy(conversationId = "other", nodeIndex = 500)))
    }

    @Test
    fun `memory and history share one complete block budget and a six excerpt ceiling`() {
        val budget = MemoryTextBudget()
        val histories = (0..9).flatMap { n -> historyMemorySlices(source.copy(
            nodeId = "node-$n", messageId = "message-$n", text = "牛奶很好喝。".repeat(100),
        )) }
        val memories = budget.slices("assistant-a", 7, 1, "牛奶很好喝。".repeat(100))
        val hits = (memories + histories).map { RankedMemory(it, 1.0) }
        val selected = selectMemoryExcerpts("牛奶", hits)
        val rendered = renderMemoryExcerpts(selected)
        assertTrue(selected.size <= 6)
        assertTrue(budget.count(rendered) <= 3000)
        assertTrue(rendered.contains("[memory #7]"))
        assertTrue(rendered.contains("[history user"))
        assertTrue(rendered.contains("conversation=conversation-a"))
        assertTrue(selected.all { it.slice.text.contains(it.text) })
    }

    @Test
    fun `different history messages must not collapse into memory zero during overlap removal`() {
        val first = historyMemorySlices(source).single()
        val second = historyMemorySlices(source.copy(nodeId = "second", messageId = "second")).single()
        val selected = selectMemoryExcerpts("牛奶", listOf(RankedMemory(first, 2.0), RankedMemory(second, 1.0)))
        assertEquals(2, selected.size)
        assertEquals(selected, selectMemoryExcerpts("牛奶", listOf(RankedMemory(first, 2.0), RankedMemory(second, 1.0))))
    }

    @Test
    fun `history windows and overlap keep unicode valid and recover hits near the end`() {
        val long = source.copy(text = "开始没有重点。".repeat(80) + "结尾喜欢燕麦牛奶😀")
        val slices = historyMemorySlices(long)
        assertTrue(slices.size > 1)
        assertTrue(slices.all { MemoryTextBudget().count(it.text) <= 800 })
        val ranked = rankKeywordMemories("燕麦牛奶", slices)
        assertTrue(ranked.isNotEmpty())
        val excerpts = selectMemoryExcerpts("燕麦牛奶", ranked)
        assertTrue(excerpts.any { it.text.contains("燕麦牛奶") })
    }

    @Test
    fun `keyword query arguments are bounded and do not consist of common single Chinese characters`() {
        val terms = historyQueryTerms("我喜欢燕麦牛奶，不喜欢咖啡。".repeat(100))
        assertTrue(terms.size <= 24)
        assertTrue(terms.all { it.codePointCount(0, it.length) > 1 && it.length <= 64 })
        assertTrue(terms.contains("牛奶"))
    }
}
