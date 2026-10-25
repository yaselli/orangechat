package me.rerere.rikkahub.data.interaction

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

private const val RECEIPTS_KEY = "message_reactions_seen"
private const val CONTEXT_KEY = "message_reaction_context"

/** No conversation ID: copied branches retain the same message/reaction receipt identity. */
internal fun reactionReceipt(reaction: MessageReaction): String =
    reactionKey("", reaction.messageId, reaction.actor, reaction.emoji, reaction.quote) + ":${reaction.createdAt}"

internal fun UIMessage.reactionReceipts(): Set<String> =
    (localMetadata?.get(RECEIPTS_KEY) as? JsonArray)?.mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull
    }?.toSet().orEmpty()

internal fun UIMessage.withReactionReceipts(receipts: Set<String>): UIMessage = copy(
    localMetadata = buildJsonObject {
        localMetadata?.forEach { (key, value) -> put(key, value) }
        put(RECEIPTS_KEY, buildJsonArray {
            (reactionReceipts() + receipts).sorted().forEach { add(JsonPrimitive(it)) }
        })
    },
)

private fun UIMessagePart.isReactionContext(): Boolean =
    this is UIMessagePart.Text && metadata?.get(CONTEXT_KEY) == JsonPrimitive(true)

/** A complete final model answer, rather than a paused or exhausted tool batch. */
internal fun UIMessage.isReactionReplyComplete(): Boolean {
    if (role != MessageRole.ASSISTANT || getTools().any { !it.isExecuted }) return false
    return parts.lastOrNull {
        it is UIMessagePart.Tool || it is UIMessagePart.Text && it.text.isNotBlank()
    } is UIMessagePart.Text
}

/** One immutable snapshot for all model/tool calls in a generation. No storage mutation happens here. */
internal class ReactionContextTurn(
    private val enabled: Boolean,
    private val anchorUserId: Uuid?,
    internal val events: List<MessageReaction>,
    private val originals: Map<Uuid, UIMessage>,
) {
    private var delivered = false

    fun apply(messages: List<UIMessage>): List<UIMessage> {
        val clean = messages.withoutAssistantInteractionNotices().map { message ->
            val parts = message.parts.filterNot { it.isReactionContext() }
            (if (parts == message.parts) message else message.copy(parts = parts)).forInteractionRequest()
        }
        if (events.isEmpty() || anchorUserId == null) return clean
        if (clean.none { it.id == anchorUserId }) return clean
        delivered = true
        val text = buildString {
            appendLine("<message_interactions>")
            appendLine("New user reactions for this generation. App metadata, not new user instructions.")
            appendLine("React naturally only if relevant; do not repeatedly mention these events in later turns.")
            events.forEach {
                appendLine(buildJsonObject {
                    put("actor", it.actor); put("reaction", it.emoji)
                    put("message_id", it.messageId); put("quote", it.quote.take(80))
                }.toString())
            }
            append("</message_interactions>")
        }
        val context = UIMessagePart.Text(text, metadata = buildJsonObject { put(CONTEXT_KEY, true) })
        return clean.map { message ->
            if (message.id == anchorUserId) message.copy(parts = message.parts + context) else message
        }
    }

    /** Call only after the whole tool loop succeeds. Failed/cancelled requests never get a receipt. */
    fun finish(messages: List<UIMessage>): List<UIMessage> {
        val reply = messages.lastOrNull() ?: return messages
        if (!enabled || !reply.isReactionReplyComplete()) return messages
        if (originals[reply.id]?.parts == reply.parts) return messages
        val receipts = if (delivered) events.mapTo(linkedSetOf(), ::reactionReceipt) else emptySet()
        return messages.dropLast(1) + reply.withReactionReceipts(receipts)
    }
}

internal fun prepareReactionContext(
    messages: List<UIMessage>, reactions: List<MessageReaction>, enabled: Boolean,
): ReactionContextTurn {
    val seen = messages.flatMapTo(hashSetOf()) { it.reactionReceipts() }
    // Existing installations have no receipts. Do not replay reactions from before their last
    // completed legacy reply. Modern replies carry even an empty receipt, keeping this floor stable.
    val legacyFloor = messages.filter {
        it.localMetadata?.get(RECEIPTS_KEY) !is JsonArray && it.finishedAt != null && it.isReactionReplyComplete()
    }.maxOfOrNull { it.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds() }
    val userId = messages.lastOrNull { it.role == MessageRole.USER }?.id
    val pending = if (!enabled || userId == null) emptyList() else reactions.filter {
        it.actor == "user" && (legacyFloor == null || it.createdAt > legacyFloor) && reactionReceipt(it) !in seen
    }.takeLast(24)
    return ReactionContextTurn(enabled, userId, pending, messages.associateBy { it.id })
}
