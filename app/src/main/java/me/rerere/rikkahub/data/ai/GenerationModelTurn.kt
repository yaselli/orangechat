package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.merge
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.handleMessageChunk

/**
 * Performs one model turn. Callers retain tool loops, approvals, transforms and persistence.
 *
 * Request history and local message state are deliberately separate: a proactive turn seeds a
 * fresh local assistant, which must never be sent upstream or merged into a previous reply.
 */
internal suspend fun generateModelTurn(
    providerImpl: Provider<ProviderSetting>,
    providerSetting: ProviderSetting,
    requestMessages: List<UIMessage>,
    initialMessages: List<UIMessage>,
    params: TextGenerationParams,
    model: Model,
    stream: Boolean,
    mergeUsage: Boolean,
    onUpdateMessages: suspend (List<UIMessage>) -> Unit,
): List<UIMessage> {
    var messages = initialMessages
    // Keep local diagnostics out of provider requests and prompt caches, preserving the original
    // list when no local fields are present (including callers' local placeholder ownership).
    val providerMessages = if (requestMessages.any { it.localMetadata != null }) {
        requestMessages.map { if (it.localMetadata == null) it else it.copy(localMetadata = null) }
    } else requestMessages

    suspend fun accept(chunk: MessageChunk) {
        messages = messages.handleMessageChunk(chunk = chunk, model = model)
        // Ordinary chat accumulates usage; proactive turns retain their existing usage policy.
        if (mergeUsage) {
            chunk.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(usage = message.usage.merge(usage))
                    } else {
                        message
                    }
                }
            }
        }
        onUpdateMessages(messages)
    }

    if (stream) {
        providerImpl.streamText(providerSetting, providerMessages, params).collect { accept(it) }
    } else {
        accept(providerImpl.generateText(providerSetting, providerMessages, params))
    }
    return messages
}
