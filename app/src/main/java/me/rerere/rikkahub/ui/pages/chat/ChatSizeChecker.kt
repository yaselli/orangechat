/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.formatNumber

@Composable
fun rememberConversationSizeInfo(conversation: Conversation): ConversationSizeInfo {
    return remember(conversation.messageNodes, conversation.contextCompaction) {
        val reply =
            conversation.messageNodes
                .asReversed()
                .firstOrNull { it.currentMessage.role == MessageRole.ASSISTANT }
                ?.currentMessage
        conversationSizeInfo(
            nodeCount = conversation.messageNodes.size,
            inputTokens = reply?.usage?.promptTokens,
            replyCreatedAtMillis =
                reply?.createdAt?.toInstant(TimeZone.currentSystemDefault())?.toEpochMilliseconds(),
            compactionCreatedAtMillis = conversation.contextCompaction?.createdAtMillis,
        )
    }
}

@Composable
fun ConversationSizeWarningDialog(
    sizeInfo: ConversationSizeInfo,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = HugeIcons.Alert01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        },
        title = {
            Text(text = stringResource(R.string.chat_size_dialog_title))
        },
        text = {
            Text(
                text =
                    stringResource(R.string.chat_size_dialog_content, sizeInfo.nodeCount) +
                        "\n\n" +
                        stringResource(
                            R.string.chat_size_dialog_input_usage,
                            sizeInfo.lastAssistantInputTokens.formatNumber(),
                        )
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        },
    )
}
