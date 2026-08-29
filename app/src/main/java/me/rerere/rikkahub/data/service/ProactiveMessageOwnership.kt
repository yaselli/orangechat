/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * Start a new assistant turn without letting [UIMessage.handleMessageChunk] reuse the id of an
 * existing assistant message when the request history itself ends with ASSISTANT.
 *
 * The placeholder is local streaming state only; callers must send [messages] (without the
 * placeholder) to the provider.
 */
internal fun beginProactiveAssistantTurn(
    messages: List<UIMessage>,
    modelId: Uuid?,
): List<UIMessage> = messages + UIMessage(
    role = MessageRole.ASSISTANT,
    parts = emptyList(),
    modelId = modelId,
)

/** Never grant this run ownership of a message that existed before the run started. */
internal fun registerProactiveRunMessageId(
    messageId: Uuid,
    protectedMessageIds: Set<Uuid>,
    runMessageIds: MutableSet<Uuid>,
): Boolean {
    if (messageId in protectedMessageIds) return false
    runMessageIds += messageId
    return true
}

/** Cleanup is limited to ids owned by this run, even if a caller accidentally supplies an old id. */
internal fun ownedProactiveCleanupIds(
    requestedIds: Collection<Uuid>,
    protectedMessageIds: Set<Uuid>,
): Set<Uuid> = requestedIds.toSet() - protectedMessageIds
