package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/** Only this generated reply may become an alternative in the explicitly selected node. */
internal data class GenerationMessageTarget(val messageId: Uuid, val nodeId: Uuid)

/**
 * Merge generation updates by message identity, never by a stale position in the request history.
 * Preserve node ids, alternatives, selection and conversation metadata. A new ordinary/proactive
 * reply appends a node; regeneration alone may add an alternative to its original target node.
 * Missing previously seen messages are deletions, not new replies. A deleted regeneration
 * target must not redirect its reply to the end of the conversation.
 * Callers must hold the session mutex while reading current state and publishing the result.
 */
internal fun Conversation.updateGenerationMessages(
    messages: List<UIMessage>,
    regenerationTarget: GenerationMessageTarget? = null,
    previouslySeenMessageIds: Set<Uuid> = emptySet(),
): Conversation {
    val nodes = messageNodes.toMutableList()
    val messageIndices = mutableMapOf<Uuid, Int>()
    val nodeIndices = mutableMapOf<Uuid, Int>()
    nodes.forEachIndexed { index, node ->
        if (node.id !in nodeIndices) nodeIndices[node.id] = index
        node.messages.forEach { message ->
            if (message.id !in messageIndices) messageIndices[message.id] = index
        }
    }
    for (message in messages) {
        val existingIndex = messageIndices[message.id]
        if (existingIndex != null) {
            val node = nodes[existingIndex]
            nodes[existingIndex] = node.copy(messages = node.messages.map {
                if (it.id == message.id) message else it
            })
        } else {
            if (message.id in previouslySeenMessageIds) continue
            val target = regenerationTarget?.takeIf { it.messageId == message.id }
            if (target != null) {
                val targetIndex = nodeIndices[target.nodeId] ?: continue
                val node = nodes[targetIndex]
                nodes[targetIndex] = node.copy(
                    messages = node.messages + message,
                    selectIndex = node.messages.size,
                )
                messageIndices[message.id] = targetIndex
            } else {
                val node = message.toMessageNode()
                nodes.add(node)
                messageIndices[message.id] = nodes.lastIndex
                nodeIndices[node.id] = nodes.lastIndex
            }
        }
    }
    return copy(messageNodes = nodes)
}
