package me.rerere.rikkahub.data.memory

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.utils.JsonInstant

@Serializable
data class SavedMemoryExcerpt(
    val memoryId: Int,
    val revision: Long,
    val sourceHash: String,
    val text: String,
)

/** A request-only copy. Never return these copies to the streaming loop or save them as chat bubbles. */
class MemoryRecallTurn(
    val scope: String? = null,
    private val messageId: String? = null,
    private val excerpts: List<SavedMemoryExcerpt> = emptyList(),
) {
    fun apply(messages: List<UIMessage>, sources: Map<Int, MemoryEntity>): List<UIMessage> {
        // Only the active user turn gets a block. Replaying every past snapshot would silently turn
        // a 3000-token per-request budget into 3000 multiplied by the number of historical turns.
        if (scope == null || messages.lastOrNull { it.role == MessageRole.USER }?.id?.toString() != messageId) {
            return messages
        }
        val block = validMemorySnapshot(excerpts, sources, scope)
        if (block.isEmpty()) return messages
        return messages.map { message ->
            if (message.role == MessageRole.USER && message.id.toString() == messageId) {
                message.copy(parts = message.parts + UIMessagePart.Text(block))
            } else message
        }
    }
}

fun memoryMessageHash(message: UIMessage): String = memoryHash(JsonInstant.encodeToString(message.parts))

/** Changes to source content (including imports that reused a revision) invalidate old snapshots. */
fun validMemorySnapshot(
    saved: List<SavedMemoryExcerpt>,
    sources: Map<Int, MemoryEntity>,
    scope: String,
): String {
    val valid = saved.filter { excerpt ->
        val source = sources[excerpt.memoryId]
        excerpt.text.isNotBlank() && source != null && source.assistantId == scope &&
            source.revision == excerpt.revision &&
            memoryHash(source.content) == excerpt.sourceHash && source.content.contains(excerpt.text)
    }
    if (valid.isEmpty()) return ""
    val block = renderMemoryRecords(valid.map { it.memoryId to it.text })
    // Also enforce the limit when reading restored or malformed snapshots.
    return block.takeIf { valid.size <= 6 && MemoryTextBudget().count(it) <= 3000 }.orEmpty()
}

/** Current text first, then a small amount of recent visible context for short or audio-only turns. */
fun memoryRecallQuery(messages: List<UIMessage>, current: UIMessage): String {
    val index = messages.indexOfFirst { it.id == current.id }
    val recent = if (index < 0) emptyList() else messages.take(index).takeLast(3).asReversed()
    val query = (listOf(current) + recent).filter {
        it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT
    }.joinToString("\n") { message ->
        message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    }
    return MemoryTextBudget().prefix(query.trim(), 2000)
}
