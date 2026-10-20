package me.rerere.rikkahub.data.memory

import kotlinx.serialization.Serializable

@Serializable
data class HistoryReference(
    val conversationId: String,
    val nodeId: String,
    val messageId: String,
    val role: String,
    val createdAt: String,
) {
    val key: String get() = "$conversationId/$nodeId/$messageId"
}

data class HistoryMemorySource(
    val conversationId: String,
    val nodeId: String,
    val messageId: String,
    val scope: String,
    val role: String,
    val createdAt: String,
    val text: String,
    val nodeIndex: Int,
) {
    val reference get() = HistoryReference(conversationId, nodeId, messageId, role, createdAt)
    val hash get() = memoryHash("$role\u0000$createdAt\u0000$text")
}

data class HistoryRecallBoundary(
    val conversationId: String,
    val maxNodeIndex: Int,
    val latestTime: String,
    val visibleMessageIds: Set<String>,
    val oldestMillis: Long? = null,
) {
    fun allows(source: HistoryMemorySource): Boolean =
        (oldestMillis == null || (organizationSourceMillis(source.createdAt) ?: Long.MIN_VALUE) >= oldestMillis) &&
        source.messageId !in visibleMessageIds &&
        source.createdAt <= latestTime &&
        (source.conversationId != conversationId || source.nodeIndex < maxNodeIndex)
}

fun historyMemorySlices(source: HistoryMemorySource, budget: MemoryTextBudget = MemoryTextBudget()): List<MemorySlice> {
    val sourceHash = source.hash
    val reference = source.reference
    return budget.slices(source.scope, 0, 0, source.text).map { slice ->
        slice.copy(
            id = memoryHash("history-v1\u0000${reference.key}\u0000$sourceHash\u0000${slice.id}"),
            sourceHash = sourceHash, history = reference,
        )
    }
}

/** Prefer meaningful words and Chinese bigrams over single-character OR matches. Bound SQL parameters. */
internal fun historyQueryTerms(query: String): List<String> = memoryTerms(query).filter {
    it.codePointCount(0, it.length) > 1 && it.length <= 64
}.take(24)
