package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/** Request-only application turn. It must never be saved or emit a user-send event. */
internal fun blockedChatReceipt(): UIMessage = UIMessage.user(
    "<application_block_receipt>这是应用自动回执，不是对方发送的消息，也不代表对方的新想法。" +
        "对方已将你拉黑，目前不能发送消息，但仍能看到你的回复。只有对方可以解除拉黑。" +
        "你可以自行决定如何回应，不必承诺或假装已经解除拉黑。" +
        "请勿将旧的用户消息理解为对方刚刚又说了一遍。" +
        "</application_block_receipt>"
)

/** Keep application text out of user templates, lorebook matching, OCR and time reminders. */
internal class BlockedChatReceiptTransformer(
    private val receipt: UIMessage,
    private val historyIds: Set<Uuid>,
    private val delegates: List<InputMessageTransformer>,
) : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        transformBlockedChatRequest(messages, receipt, historyIds) { history ->
            delegates.fold(history) { result, transformer -> transformer.transform(ctx, result) }
        }
}

internal suspend fun transformBlockedChatRequest(
    messages: List<UIMessage>,
    receipt: UIMessage,
    historyIds: Set<Uuid>,
    transformHistory: suspend (List<UIMessage>) -> List<UIMessage>,
): List<UIMessage> {
    // The receipt may be outside the context window on later tool steps. Restore it BEFORE
    // the new assistant/tool turn, never between a tool call and its result or at the tail.
    val generatedIds = messages.filter { it.role == MessageRole.ASSISTANT && it.id !in historyIds }
        .mapTo(hashSetOf()) { it.id }
    val transformed = transformHistory(messages.filterNot { it.id == receipt.id })
    val insertionIndex = transformed.indexOfFirst { it.id in generatedIds }.takeIf { it >= 0 }
        ?: transformed.size
    return transformed.take(insertionIndex) + receipt + transformed.drop(insertionIndex)
}

internal fun isCompletedBlockedReply(reply: UIMessage?, previousId: Uuid?): Boolean =
    reply != null && reply.id != previousId && reply.role == MessageRole.ASSISTANT &&
        reply.finishedAt != null && reply.getTools().all { it.isExecuted } &&
        reply.parts.filterIsInstance<UIMessagePart.Text>().any {
            it.text.isNotBlank() && it.text.trim() != "[SKIP]"
        }

internal fun applyBlockedChatChunk(
    conversation: Conversation,
    messages: List<UIMessage>,
    receiptId: Uuid,
): Conversation = conversation.updateCurrentMessages(messages.filterNot { it.id == receiptId })
