package me.rerere.rikkahub.data.memory

import me.rerere.rikkahub.data.db.entity.MemoryEntity
import org.junit.Assert.*
import org.junit.Test

class MemoryRecallEvidenceTest {
    private val original = HistoryMemorySource("chat", "node", "message", "owner", "user",
        "2020-01-01T00:00", "我不吃香菜，闻着就难受。", 0)
    private val memory = MemoryEntity(7, "owner", "她不吃香菜。", sourceKind = "automatic",
        sourceConversationId = "chat", sourceMessageId = "message")
    private val record = MemoryEvidence(7, "owner", "chat", "node", "message", original.hash,
        "user", original.createdAt, "我不吃香菜，闻着就难受。")
    private val originals = mapOf(original.reference.key to original)

    @Test
    fun `evidence requires exact memory ownership revision identity and literal quotation`() {
        assertEquals(record.savedRecallEvidence(), verifiedRecallEvidence(memory, record, originals, "owner"))
        assertNull(verifiedRecallEvidence(memory.copy(revision = 2), record, originals, "owner"))
        assertNull(verifiedRecallEvidence(memory, record.copy(owner = "foreign"), originals, "owner"))
        assertNull(verifiedRecallEvidence(memory, record.copy(quote = "我喜欢香菜"), originals, "owner"))
        assertNull(verifiedRecallEvidence(memory, record.copy(messageId = "another-version"), originals, "owner"))
        assertNull(verifiedRecallEvidence(memory, record, emptyMap(), "owner"))
        assertNull(verifiedRecallEvidence(memory, null, originals, "owner"))
    }

    @Test
    fun `whole evidence groups fit together without truncating unicode quotations`() {
        val quotation = "字".repeat(598) + "🍗"
        val excerpt = SavedMemoryExcerpt(7, 1, "hash", "炸鸡", evidence = record.savedRecallEvidence().copy(quote = quotation),
            evidenceState = MemoryEvidenceState.VERIFIED)
        val groups = fitMemoryEvidenceGroups(listOf(excerpt, excerpt.copy(memoryId = 8)))
        assertEquals(1, groups.size)
        assertEquals(quotation, groups.single().evidence?.quote)
        assertTrue(MemoryTextBudget().count(renderMemorySnapshot(groups)) <= 3000)
        assertTrue(fitMemoryEvidenceGroups(listOf(excerpt.copy(text = "x".repeat(4000)))).isEmpty())
    }

    @Test
    fun `old memories without evidence are labelled and never borrow neighbouring text`() {
        val excerpt = SavedMemoryExcerpt(7, 1, memoryHash(memory.content), memory.content)
        val output = renderMemorySnapshot(listOf(excerpt))
        assertTrue(output.contains("no verifiable source quotation"))
        assertFalse(output.contains(record.quote))
    }
}
