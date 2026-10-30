package me.rerere.rikkahub.data.memory

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.db.entity.MemoryEntity

@Serializable
enum class MemoryEvidenceState { VERIFIED, MISSING, STALE }

@Serializable
data class SavedRecallEvidence(val source: HistoryReference, val sourceHash: String, val quote: String)

internal fun MemoryEvidence.savedRecallEvidence() = SavedRecallEvidence(
    HistoryReference(conversationId, nodeId, messageId, role, sourceCreatedAt), sourceHash, quote,
)

/** Source identity and the memory revision must match. A true quotation alone does not prove a fact is true. */
internal fun verifiedRecallEvidence(
    memory: MemoryEntity, record: MemoryEvidence?, originals: Map<String, HistoryMemorySource>, owner: String?,
): SavedRecallEvidence? {
    if (record == null || record.owner != owner || record.memoryId != memory.id || memory.revision != 1L ||
        memory.sourceKind != "automatic" || memory.sourceConversationId != record.conversationId ||
        memory.sourceMessageId != record.messageId) return null
    val saved = record.savedRecallEvidence()
    val original = originals[saved.source.key] ?: return null
    return saved.takeIf {
        original.scope == owner && original.reference == saved.source && original.hash == saved.sourceHash &&
            saved.quote.isNotBlank() && saved.quote.length <= 600 && original.text.contains(saved.quote)
    }
}

/** Apply a single budget to whole memory/evidence groups. Never truncate or substitute the quotation. */
internal fun fitMemoryEvidenceGroups(excerpts: List<SavedMemoryExcerpt>): List<SavedMemoryExcerpt> {
    val result = mutableListOf<SavedMemoryExcerpt>()
    for (excerpt in excerpts) {
        if (result.size >= 6) break
        if (renderMemorySnapshot(result + excerpt).isNotEmpty()) result += excerpt
    }
    return result
}
