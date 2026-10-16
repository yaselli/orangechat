package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.JsonInstant
import java.security.MessageDigest

/** Request-only summary. The original message nodes remain the source of truth. */
@Serializable
data class ContextCompaction(
    val summary: String,
    val coveredMessages: List<CompactedMessageRef>,
    val createdAtMillis: Long,
) {
    fun matches(messages: List<UIMessage>): Boolean = summary.isNotBlank() &&
        coveredMessages.isNotEmpty() && messages.size >= coveredMessages.size &&
        coveredMessages.indices.all { index -> coveredMessages[index] == messages[index].compactionRef() }
}

@Serializable
data class CompactedMessageRef(val id: String, val contentHash: String)

private fun UIMessage.compactionRef(): CompactedMessageRef {
    // Hash request content, not UI-only translation/usage. Editing a selected branch invalidates its summary.
    val content = JsonInstant.encodeToString(copy(translation = null, usage = null, finishedAt = null))
    val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
    return CompactedMessageRef(id.toString(), digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
}

data class CompactedContext(val summary: String?, val messages: List<UIMessage>)

/** Shared by regular chat and proactive chat; invalid summaries fall back to full history. */
fun selectCompactedContext(messages: List<UIMessage>, compaction: ContextCompaction?): CompactedContext =
    if (compaction?.matches(messages) == true) {
        CompactedContext(compaction.summary, messages.drop(compaction.coveredMessages.size))
    } else {
        CompactedContext(null, messages)
    }

data class ContextCompactionPlan(
    val previous: ContextCompaction?,
    val previousSummary: String?,
    val coveredMessages: List<CompactedMessageRef>,
    val newMessages: List<UIMessage>,
) {
    fun result(summary: String): ContextCompaction {
        require(summary.isNotBlank()) { "压缩模型返回了空摘要，原对话未改变" }
        return ContextCompaction(summary.trim(), coveredMessages, System.currentTimeMillis())
    }
}

/** Cut only between complete turns, never between a tool call and its continuation. */
fun Conversation.planCompaction(keepRecentMessages: Int): ContextCompactionPlan {
    require(keepRecentMessages >= 0)
    val messages = currentMessages
    var end = (messages.size - keepRecentMessages).coerceAtLeast(0)
    while (end > 0 && (
        messages[end - 1].role != MessageRole.ASSISTANT ||
            messages[end - 1].getTools().any { !it.isExecuted } ||
            (end < messages.size && messages[end].role != MessageRole.USER)
        )) end--
    val old = contextCompaction?.takeIf { it.matches(messages) }
    val start = old?.coveredMessages?.size ?: 0
    require(end > start) { "没有可压缩的新对话，请减少保留条数或继续聊天后再试" }
    require(messages.take(end).all { message -> message.getTools().all { it.isExecuted } }) {
        "待压缩范围还有未完成的工具调用，请先处理或取消该轮回复"
    }
    return ContextCompactionPlan(
        previous = contextCompaction,
        previousSummary = old?.summary,
        coveredMessages = messages.take(end).map { it.compactionRef() },
        newMessages = messages.subList(start, end),
    )
}
