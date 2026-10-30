package me.rerere.rikkahub.data.message

import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private const val QUOTE_METADATA = "watermelon.messageQuote.v1"

/** Kept with the sent message, not in assistant settings or a persistent prompt. */
data class MessageQuote(val messageId: String, val author: String, val text: String) {
    fun toPart(): UIMessagePart.Text =
        UIMessagePart.Text(
            // Empty by design: only the current-turn request projection expands the quote.
            // History, memory retrieval and later turns cannot accidentally resend its body.
            text = "",
            metadata =
                buildJsonObject {
                    put(
                        QUOTE_METADATA,
                        buildJsonObject {
                            put("messageId", messageId)
                            put("author", author)
                            put("text", text)
                        },
                    )
                },
        )

    internal fun requestPart(): UIMessagePart.Text =
        UIMessagePart.Text(
            "[引用消息，仅供理解本条回复]\n${author.lineSequence().joinToString(" ")}:\n" +
                text.lineSequence().joinToString("\n") { "> $it" }
        )
}

fun UIMessagePart.messageQuote(): MessageQuote? {
    if (this !is UIMessagePart.Text) return null
    val data = metadata?.get(QUOTE_METADATA) as? JsonObject ?: return null
    fun field(name: String): String? =
        (data[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val id = field("messageId")?.takeIf { it.isNotBlank() } ?: return null
    val text = field("text")?.takeIf { it.isNotBlank() } ?: return null
    return MessageQuote(id, field("author").orEmpty(), text)
}

/** Capture once at generation start, so tool steps share the same turn without a sticky quote. */
fun List<UIMessage>.quoteTurnUserId(): Uuid? {
    val tail = lastOrNull() ?: return null
    if (tail.role == MessageRole.USER) return tail.id
    // An approved/pending tool resumes the same answer after an interruption.
    if (
        tail.role == MessageRole.ASSISTANT &&
            tail.parts.any {
                it is UIMessagePart.Tool &&
                    !it.isExecuted &&
                    (it.isPending || it.canResumeExecution)
            }
    )
        return lastOrNull { it.role == MessageRole.USER }?.id
    return null
}

/** A provider-only copy. Stored cards survive; all old quote bodies are omitted from requests. */
fun List<UIMessage>.withCurrentTurnQuote(userMessageId: Uuid?): List<UIMessage> = map { message ->
    if (message.parts.none { it.messageQuote() != null }) message
    else
        message.copy(
            parts =
                message.parts.mapNotNull { part ->
                    val quote = part.messageQuote()
                    when {
                        quote == null -> part
                        message.role == MessageRole.USER && message.id == userMessageId ->
                            quote.requestPart()
                        else -> null
                    }
                }
        )
}
