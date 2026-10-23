package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.core.sum
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
    nanoTime: () -> Long = System::nanoTime,
    onUpdateMessages: suspend (List<UIMessage>) -> Unit,
): List<UIMessage> {
    var messages = initialMessages
    val previous = initialMessages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
    var requestUsage: TokenUsage? = null
    var startedAt: Long? = null
    var updateNanos = 0L
    // Keep local diagnostics out of provider requests and prompt caches, preserving the original
    // list when no local fields are present (including callers' local placeholder ownership).
    val providerMessages = if (requestMessages.any { it.localMetadata != null || it.generationTimeMs != null }) {
        requestMessages.map { it.copy(localMetadata = null, generationTimeMs = null) }
    } else requestMessages

    fun applyStatistics(): Boolean {
        val last = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT } ?: return false
        val base = previous?.takeIf { it.id == last.id }
        val elapsed = startedAt?.let { ((nanoTime() - it - updateNanos) / 1_000_000).coerceAtLeast(0) }
        val updated = last.copy(
            usage = if (mergeUsage) base?.usage.sum(requestUsage) else last.usage,
            generationTimeMs = elapsed?.let { (base?.generationTimeMs ?: 0) + it }
                ?: last.generationTimeMs,
        )
        if (updated == last) return false
        messages = messages.dropLast(1) + updated
        return true
    }

    suspend fun publish() {
        val before = nanoTime()
        onUpdateMessages(messages)
        updateNanos += (nanoTime() - before).coerceAtLeast(0)
    }

    suspend fun accept(chunk: MessageChunk) {
        if (startedAt == null) startedAt = nanoTime()
        chunk.usage?.let { requestUsage = requestUsage.merge(it) }
        messages = messages.handleMessageChunk(chunk = chunk, model = model)
        applyStatistics()
        publish()
    }

    if (stream) {
        providerImpl.streamText(providerSetting, providerMessages, params).collect { accept(it) }
    } else {
        // Nonstreaming APIs cannot report first-token time; measure the entire request instead.
        startedAt = nanoTime()
        accept(providerImpl.generateText(providerSetting, providerMessages, params))
    }
    // The final usage frame can precede stream completion. Never retry a cancelled/failed callback.
    if (startedAt != null && applyStatistics()) publish()
    return messages
}
