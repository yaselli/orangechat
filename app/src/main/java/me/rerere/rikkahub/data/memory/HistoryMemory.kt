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
) {
    fun allows(source: HistoryMemorySource): Boolean = source.messageId !in visibleMessageIds &&
        source.createdAt <= latestTime &&
        (source.conversationId != conversationId || source.nodeIndex < maxNodeIndex)
}

fun historyMemorySlices(source: HistoryMemorySource, budget: MemoryTextBudget = MemoryTextBudget()): List<MemorySlice> =
    budget.slices(source.scope, 0, 0, source.text).map { slice ->
        slice.copy(
            id = memoryHash("history-v1\u0000${source.reference.key}\u0000${source.hash}\u0000${slice.id}"),
            sourceHash = source.hash, history = source.reference,
        )
    }

/** Prefer meaningful words and Chinese bigrams over single-character OR matches. Bound SQL parameters. */
internal fun historyQueryTerms(query: String): List<String> = memoryTerms(query).filter {
    it.codePointCount(0, it.length) > 1 && it.length <= 64
}.take(24)
