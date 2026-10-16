package me.rerere.rikkahub.data.interaction

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.splitIntoBubbleSegments
import me.rerere.rikkahub.data.ai.transformers.isExtraInfoInjectionPart

val CHAT_REACTION_EMOJIS = listOf("❤️", "👍", "👎", "😂", "🥰", "😢", "😡", "😮", "🥛", "🤗", "🤔", "🎉")

fun UIMessage.avatarInteraction(): UIMessageAnnotation.AvatarInteraction? =
    annotations.filterIsInstance<UIMessageAnnotation.AvatarInteraction>().firstOrNull()

/** AI-created notices are display events, never new system instructions for the provider. */
fun List<UIMessage>.withoutAssistantInteractionNotices(): List<UIMessage> = filterNot {
    it.role == MessageRole.SYSTEM && it.avatarInteraction() != null
}

fun reactionSegments(message: UIMessage, assistant: Assistant): List<String> = message.parts
    .filterIsInstance<UIMessagePart.Text>().filterNot { it.isExtraInfoInjectionPart() }.flatMap {
        val text = it.text.replace(Regex("\\[zip:[^\\]]+\\]", RegexOption.IGNORE_CASE), "")
        val split = if (message.role == MessageRole.USER) assistant.splitUserBubbleByLine
            else assistant.splitBubbleByLine
        if (split) text.splitIntoBubbleSegments() else listOf(text)
    }.filter { it.isNotBlank() }

fun resolveReactionTarget(
    messages: List<UIMessage>, assistant: Assistant, target: String, quote: String,
): UIMessage {
    val eligible = messages.filter {
        it.avatarInteraction() == null && it.role in listOf(MessageRole.USER, MessageRole.ASSISTANT)
    }
    return when (target) {
        "last_user" -> eligible.lastOrNull { it.role == MessageRole.USER }
        "last_assistant" -> eligible.lastOrNull { it.role == MessageRole.ASSISTANT }
        "by_quote" -> {
            require(quote.isNotBlank()) { "by_quote 需要指定原句" }
            val matches = eligible.filter { reactionSegments(it, assistant).any { segment -> segment.contains(quote) } }
            require(matches.size == 1) { "原句未找到或不唯一，请指定消息 ID" }
            matches.single()
        }
        else -> eligible.firstOrNull { it.id.toString() == target }
    } ?: error("目标消息不存在或不在当前分支")
}

fun resolveReactionQuote(message: UIMessage, assistant: Assistant, quote: String): String {
    val segments = reactionSegments(message, assistant)
    if (quote.isEmpty()) return segments.lastOrNull().orEmpty()
    return segments.firstOrNull { it == quote } ?: segments.filter { it.contains(quote) }.let { matches ->
        require(matches.size == 1) { "请指定唯一的一句原文" }
        matches.single()
    }
}
