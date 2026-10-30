package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.ChatDisplayMode
import me.rerere.rikkahub.data.interaction.MessageReaction
import me.rerere.rikkahub.data.message.MessageQuote
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.utils.chatBubbleSegments

internal fun chatBubbleShape(isUser: Boolean) =
    RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomEnd = if (isUser) 4.dp else 20.dp,
        bottomStart = if (isUser) 20.dp else 4.dp,
    )

/** One row per visible bubble; never share an avatar between segments. */
@Composable
internal fun ChatBubbleRow(
    enabled: Boolean,
    isUser: Boolean,
    avatar: @Composable () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .then(
                    if (onLongClick == null) Modifier
                    else Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
                ),
        horizontalArrangement =
            Arrangement.spacedBy(8.dp, if (isUser) Alignment.End else Alignment.Start),
        verticalAlignment = Alignment.Top,
    ) {
        if (isUser) Spacer(Modifier.width(16.dp)) else avatar()
        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            content()
        }
        if (isUser) avatar() else Spacer(Modifier.width(16.dp))
    }
}

@Composable
internal fun ChatMessageTextBubbles(
    text: String,
    role: MessageRole,
    assistant: Assistant?,
    reactions: List<MessageReaction>,
    onReact: ((String, String) -> Unit)?,
    onUserMessageClick: (() -> Unit)?,
    onClickCitation: (String) -> Unit,
    avatar: @Composable () -> Unit,
    onLongClick: ((String) -> Unit)?,
    quote: MessageQuote? = null,
) {
    val display = LocalDisplaySettings.current
    val everyday = display.chatDisplayMode == ChatDisplayMode.EVERYDAY
    val isUser = role == MessageRole.USER
    val split =
        (if (isUser) assistant?.splitUserBubbleByLine else assistant?.splitBubbleByLine) == true
    val segments =
        remember(text, display.chatDisplayMode, split) {
            text.chatBubbleSegments(display.chatDisplayMode, split).let { segments ->
                if (everyday) segments.filter { it.isNotBlank() } else segments
            }
        }
    val showBubble = isUser || everyday || display.showAssistantBubble
    val scheme = MaterialTheme.colorScheme
    BubbleTextSelection(enabled = !everyday && onLongClick == null) {
        Column(
            verticalArrangement = Arrangement.spacedBy(if (everyday) 6.dp else 4.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            segments.forEachIndexed { index, segment ->
                key(index) {
                    val visibleSegment =
                        segment.replaceRegexes(
                            assistant = assistant,
                            scope =
                                if (isUser) AssistantAffectScope.USER
                                else AssistantAffectScope.ASSISTANT,
                            visual = true,
                        )
                    ChatBubbleRow(everyday, isUser, avatar) {
                        if (showBubble) {
                            BubbleSurface(
                                imagePath =
                                    if (isUser) display.userBubbleImagePath
                                    else display.assistantBubbleImagePath,
                                cornerRadius = display.bubbleCornerRadius.dp,
                                color =
                                    if (isUser)
                                        display.userBubbleColor?.toComposeColor()
                                            ?: scheme.secondaryContainer
                                    else
                                        display.assistantBubbleColor?.toComposeColor()
                                            ?: scheme.surfaceContainerHigh,
                                overlayEnabled = display.bubbleImageOverlayEnabled,
                                bubbleAlpha = 1f - display.chatBubbleTransparency / 100f,
                                onClick = if (isUser) onUserMessageClick else null,
                                onLongClick =
                                    onLongClick?.let { callback -> { callback(visibleSegment) } },
                                shape = if (everyday) chatBubbleShape(isUser) else null,
                            ) {
                                if (index == 0 && quote != null) MessageQuoteCard(quote)
                                MarkdownBlock(
                                    content = visibleSegment,
                                    onClickCitation = onClickCitation,
                                )
                            }
                        } else {
                            if (index == 0 && quote != null) MessageQuoteCard(quote)
                            MarkdownBlock(
                                content = visibleSegment,
                                onClickCitation = onClickCitation,
                                modifier =
                                    Modifier.animateContentSize()
                                        .then(
                                            onLongClick?.let { callback ->
                                                Modifier.combinedClickable(
                                                    onClick = {},
                                                    onLongClick = { callback(visibleSegment) },
                                                )
                                            } ?: Modifier
                                        ),
                            )
                        }
                        MessageReactionBar(segment, reactions, onReact)
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleTextSelection(enabled: Boolean, content: @Composable () -> Unit) {
    if (enabled) SelectionContainer(content = content) else content()
}
