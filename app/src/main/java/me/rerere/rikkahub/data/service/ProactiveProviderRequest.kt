package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * Gemini rejects requests ending in a model turn. Proactive history normally ends in the
 * assistant's last reply, unlike normal chat, which has a new user turn.
 *
 * The USER transport role below represents an explicitly labelled system event, never a real
 * user message. Insert it only into the provider copy, after recall/interaction decoration.
 * Keep it out of local streaming state, memory queries, persisted history and idle timers.
 */
internal class ProactiveProviderRequest private constructor(
    private val historyTailId: Uuid,
    private val event: UIMessage,
) {
    fun applyTo(messages: List<UIMessage>): List<UIMessage> {
        if (messages.any { it.id == event.id }) return messages
        val boundary = messages.indexOfLast { it.id == historyTailId }
        if (boundary < 0) return messages

        // Subsequent tool rounds must retain this original boundary. Appending a new event
        // after every tool result would interrupt the model's active function-call turn.
        return messages.take(boundary + 1) + event + messages.drop(boundary + 1)
    }

    companion object {
        fun create(
            history: List<UIMessage>,
            modelId: String,
            googleProvider: Boolean,
        ): ProactiveProviderRequest? {
            if (!googleProvider && !modelId.contains("gemini", ignoreCase = true)) return null
            val tail = history.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT } ?: return null
            return ProactiveProviderRequest(
                historyTailId = tail.id,
                event = UIMessage.user(
                    "【系统后台事件，不是对方的新发言】主动消息闹钟已触发。" +
                        "请依照系统提示完成本次主动消息判断，不要重新回答上一条用户消息。",
                ),
            )
        }
    }
}
