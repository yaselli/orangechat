/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

// 部分供应商不会返回reasoning parts, 所以需要这个transformer
object ThinkTagTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return transformThinkTags(messages, finished = false)
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return transformThinkTags(messages, finished = true)
    }
}

internal fun transformThinkTags(messages: List<UIMessage>, finished: Boolean): List<UIMessage> {
    val now = Clock.System.now()
    return messages.map { message ->
        if (message.role != MessageRole.ASSISTANT) return@map message
        val createdAt = message.createdAt.toInstant(TimeZone.currentSystemDefault())
        var reasoningIndex = 0
        message.copy(
            parts =
                message.parts.flatMap { part ->
                    if (part !is UIMessagePart.Text) return@flatMap listOf(part)
                    val segments = splitThinkingText(part.text)
                    if (segments.none { it.reasoning }) return@flatMap listOf(part)
                    segments.map { segment ->
                        if (segment.reasoning)
                            UIMessagePart.Reasoning(
                                reasoning = segment.text,
                                // The UI keys reasoning steps by createdAt; keep multiple tag
                                // blocks distinct and stable.
                                createdAt = createdAt + (reasoningIndex++).nanoseconds,
                                finishedAt = if (finished || segment.closed) now else null,
                                metadata = part.metadata,
                            )
                        else part.copy(text = segment.text)
                    }
                }
        )
    }
}
