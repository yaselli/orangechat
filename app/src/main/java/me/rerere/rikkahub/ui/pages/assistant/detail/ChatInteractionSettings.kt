package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Composable
internal fun ChatInteractionSettings(assistant: Assistant, onEdit: ((Assistant) -> Assistant) -> Unit) {
    CardGroup {
        item(
            headlineContent = { Text("头像互动") },
            supportingContent = { Text("双击头像互动，AI 也能调用工具对你使用。双击会触发一次回复。") },
            trailingContent = { Switch(assistant.enableAvatarInteractions, {
                onEdit { current -> current.copy(enableAvatarInteractions = it) }
            }) },
        )
        item(
            headlineContent = { Text("消息表情回应") },
            supportingContent = { Text("表情贴在对应气泡下面，AI 下一次回复时能看见。") },
            trailingContent = { Switch(assistant.enableMessageReactions, {
                onEdit { current -> current.copy(enableMessageReactions = it) }
            }) },
        )
    }
    key(assistant.id) {
        if (assistant.enableAvatarInteractions) Column(
            Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("你对 AI 的互动", style = MaterialTheme.typography.titleSmall)
            InteractionTextField(assistant.userInteractionAction, "动作，例如：揉了揉", 20, singleLine = true) { text ->
                onEdit { it.copy(userInteractionAction = text) }
            }
            InteractionTextField(assistant.userInteractionSuffix, "后缀，例如：的脑袋，并递来一杯牛奶", 80) { text ->
                onEdit { it.copy(userInteractionSuffix = text) }
            }
            Text("AI 对你的互动", style = MaterialTheme.typography.titleSmall)
            InteractionTextField(assistant.assistantInteractionAction, "动作，例如：拍了拍", 20, singleLine = true) { text ->
                onEdit { it.copy(assistantInteractionAction = text) }
            }
            InteractionTextField(assistant.assistantInteractionSuffix, "后缀，例如：的肩膀，并抱了抱你", 80) { text ->
                onEdit { it.copy(assistantInteractionSuffix = text) }
            }
        }
    }
}

/** The editor owns selection/composition; delayed persistence must never replace its active buffer. */
@Composable
internal fun InteractionTextField(
    initialText: String,
    label: String,
    maxLength: Int,
    singleLine: Boolean = false,
    onChange: (String) -> Unit,
) {
    val state = rememberTextFieldState(initialText)
    val latestOnChange by rememberUpdatedState(onChange)
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.drop(1).collect { latestOnChange(it) }
    }
    OutlinedTextField(
        state = state,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        inputTransformation = InputTransformation.maxLength(maxLength),
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(
            maxHeightInLines = 3,
        ),
    )
}
