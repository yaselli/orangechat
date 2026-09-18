package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.model.Assistant

internal enum class GenerationScene { CHAT, PROACTIVE }

private const val MAX_PROACTIVE_CONTEXT_MESSAGES = 20

/** Select the role prompt without changing whitespace or adding scene instructions. */
internal fun selectGenerationSystemPrompt(
    assistant: Assistant,
    scene: GenerationScene,
    conversationSystemPrompt: String? = null,
): String = if (
    scene == GenerationScene.CHAT && assistant.allowConversationSystemPrompt &&
    !conversationSystemPrompt.isNullOrBlank()
) {
    conversationSystemPrompt
} else {
    assistant.systemPrompt
}

/** Select request history only; never mutate persisted messages or sanitize tool protocols here. */
internal fun selectGenerationHistory(
    messages: List<UIMessage>,
    contextMessageSize: Int,
    scene: GenerationScene,
): List<UIMessage> = when (scene) {
    // Keep the existing tool-dependency-aware truncation for regular chat.
    GenerationScene.CHAT -> messages.limitContext(contextMessageSize)
    // Proactive provider/tool sanitization still runs in its caller after this selection.
    GenerationScene.PROACTIVE -> messages.takeLast(
        (contextMessageSize.takeIf { it > 0 } ?: MAX_PROACTIVE_CONTEXT_MESSAGES)
            .coerceAtMost(MAX_PROACTIVE_CONTEXT_MESSAGES)
    )
}
