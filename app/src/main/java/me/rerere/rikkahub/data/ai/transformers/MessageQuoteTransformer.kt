package me.rerere.rikkahub.data.ai.transformers

import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.message.withCurrentTurnQuote

/** This transformer is created per generation, including all its tool continuations. */
class MessageQuoteTransformer(private val userMessageId: Uuid?) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.withCurrentTurnQuote(userMessageId)
}
