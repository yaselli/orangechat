package me.rerere.rikkahub.data.memory

import me.rerere.ai.provider.providers.openai.RerankScore
import me.rerere.ai.provider.providers.openai.parseRerankScores
import org.junit.Assert.*
import org.junit.Test

class MemoryRerankTest {
    @Test fun `parser rejects malformed duplicate foreign or non finite scores and ignores server document replacement`() {
        val good = parseRerankScores("""{"results":[{"index":0,"relevance_score":0.9,"document":{"text":"untrusted replacement"}}]}""", 1)
        assertEquals(listOf(RerankScore(0, 0.9)), good)
        for (raw in listOf("bad", """{"results":[{"index":8,"relevance_score":0.9}]}""",
            """{"results":[{"index":0,"relevance_score":2}]}""",
            """{"results":[{"index":0,"relevance_score":0.9},{"index":0,"relevance_score":0.8}]}""")) {
            assertThrows(Exception::class.java) { parseRerankScores(raw, 2) }
        }
        assertTrue(parseRerankScores("""{"results":[]}""", 1).isEmpty())
    }
    @Test fun `separate route quotas deduplicate original text and final selection never forces low scores`() {
        val budget = MemoryTextBudget()
        val longTerm = (1..20).map { RankedMemory(budget.slices("owner", it, 1, "长期记忆$it").single(), 1.0) }
        val source = HistoryMemorySource("chat", "node", "message", "owner", "assistant", "2026-10-19", "历史原文", 0)
        val historical = historyMemorySlices(source).map { RankedMemory(it, 1.0) }
        val candidates = memoryRerankCandidates("记忆", longTerm, historical)
        assertEquals(7, candidates.size)
        assertEquals(1, candidates.count { it.slice.history != null })
        assertTrue(selectRerankedMemory(candidates, listOf(RerankScore(0, 0.1)), 0.5, 3).isEmpty())
        val scores = candidates.indices.map { RerankScore(it, 0.9) }
        val selected = selectRerankedMemory(candidates, scores, 0.5, 3)
        assertTrue(selected.size <= 3)
        assertTrue(renderMemorySnapshot(selected).toByteArray().size <= 3000)
        val duplicate = longTerm.first().copy(slice = longTerm.first().slice.copy(memoryId = 99))
        assertEquals(1, memoryRerankCandidates("记忆", listOf(longTerm.first(), duplicate), emptyList()).size)
    }
    @Test fun `identical first person text by different speakers retains both roles`() {
        val user = HistoryMemorySource("chat", "n1", "m1", "owner", "user", "2026-10-19", "我喜欢吃辣", 0)
        val assistant = user.copy(nodeId = "n2", messageId = "m2", role = "assistant", nodeIndex = 1)
        val hits = (historyMemorySlices(user) + historyMemorySlices(assistant)).map { RankedMemory(it, 1.0) }
        val candidates = memoryRerankCandidates("吃辣", emptyList(), hits)
        assertEquals(setOf("user", "assistant"), candidates.map { it.slice.history?.role }.toSet())
        assertEquals(2, selectRerankedMemory(candidates, listOf(RerankScore(0, 0.9), RerankScore(1, 0.8)), 0.5, 3).size)
    }

}
