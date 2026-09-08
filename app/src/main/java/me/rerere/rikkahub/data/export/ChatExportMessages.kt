package me.rerere.rikkahub.data.export

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.isExtraInfoInjectionPart

/** Presentation-only copies: never alter stored history or classify ordinary text by its tags. */
internal fun prepareChatExportMessages(messages: List<UIMessage>): List<UIMessage> =
    messages.mapNotNull { message ->
        val visibleParts = message.parts.filterNot { it.isExtraInfoInjectionPart() }
        when {
            visibleParts.size == message.parts.size -> message
            visibleParts.isEmpty() -> null
            else -> message.copy(parts = visibleParts)
        }
    }
