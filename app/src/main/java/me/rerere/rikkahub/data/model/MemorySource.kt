package me.rerere.rikkahub.data.model

/** The request that triggered this write, not a claim that its user message proves the stored fact. */
data class MemorySource(
    val kind: String = "manual",
    val conversationId: String = "",
    val messageId: String = "",
)
