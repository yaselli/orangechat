/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.canResumeToolExecution

/**
 * Remove historical messages whose tool calls are incomplete and cannot be resumed.
 *
 * A message with at least one approved, denied, or answered tool is retained so the normal
 * generation loop can finish that pending interaction. This mirrors ChatService's existing
 * invalid-message policy without depending on Android or a database.
 */
internal fun filterInvalidToolMessages(messages: List<UIMessage>): List<UIMessage> {
    return messages.filterNot { message ->
        val tools = message.getTools()
        val hasPendingTools = tools.any { !it.isExecuted }
        if (!hasPendingTools) return@filterNot false

        val hasResumableTool = tools.any {
            !it.isExecuted && it.approvalState.canResumeToolExecution()
        }
        !hasResumableTool
    }
}

/** Merge adjacent messages with the same role for providers that require alternating roles. */
internal fun mergeAdjacentSameRoleMessages(messages: List<UIMessage>): List<UIMessage> {
    if (messages.size < 2) return messages

    return messages.fold(emptyList()) { accumulated, message ->
        val previous = accumulated.lastOrNull()
        if (previous != null && previous.role == message.role) {
            accumulated.dropLast(1) + previous.copy(parts = previous.parts + message.parts)
        } else {
            accumulated + message
        }
    }
}
