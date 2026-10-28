package me.rerere.rikkahub.ui.pages.chat

const val MESSAGE_NODE_WARNING_THRESHOLD = 768
const val LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD = 300_000

data class ConversationSizeInfo(
    val nodeCount: Int,
    val lastAssistantInputTokens: Int,
    val exceedNodeCountThreshold: Boolean,
    val exceedInputTokenThreshold: Boolean,
    val showWarning: Boolean,
)

/** A summary changes the request history; usage from before that change is no longer evidence. */
internal fun conversationSizeInfo(
    nodeCount: Int,
    inputTokens: Int?,
    replyCreatedAtMillis: Long?,
    compactionCreatedAtMillis: Long?,
): ConversationSizeInfo {
    val usageIsCurrent =
        compactionCreatedAtMillis == null ||
            (replyCreatedAtMillis != null && replyCreatedAtMillis > compactionCreatedAtMillis)
    val tokens = if (usageIsCurrent) (inputTokens ?: 0).coerceAtLeast(0) else 0
    val tooManyNodes = nodeCount > MESSAGE_NODE_WARNING_THRESHOLD
    val tooManyTokens = tokens > LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD
    return ConversationSizeInfo(
        nodeCount,
        tokens,
        tooManyNodes,
        tooManyTokens,
        tooManyNodes && tooManyTokens,
    )
}
