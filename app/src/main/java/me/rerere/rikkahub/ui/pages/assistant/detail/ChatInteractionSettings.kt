package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Composable
internal fun ChatInteractionSettings(assistant: Assistant, onUpdate: (Assistant) -> Unit) {
    CardGroup {
        item(
            headlineContent = { Text("头像互动") },
            supportingContent = { Text("双击头像互动，AI 也能调用工具对你使用。双击会触发一次回复。") },
            trailingContent = { Switch(assistant.enableAvatarInteractions, {
                onUpdate(assistant.copy(enableAvatarInteractions = it))
            }) },
        )
        item(
            headlineContent = { Text("消息表情回应") },
            supportingContent = { Text("表情贴在对应气泡下面，AI 下一次回复时能看见。") },
            trailingContent = { Switch(assistant.enableMessageReactions, {
                onUpdate(assistant.copy(enableMessageReactions = it))
            }) },
        )
    }
    if (assistant.enableAvatarInteractions) Column(
        Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("你对 AI 的互动", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = assistant.userInteractionAction, label = { Text("动作，例如：揉了揉") }, singleLine = true,
            onValueChange = { if (it.length <= 20) onUpdate(assistant.copy(userInteractionAction = it)) },
        )
        OutlinedTextField(
            value = assistant.userInteractionSuffix, label = { Text("后缀，例如：的脑袋，并递来一杯牛奶") },
            onValueChange = { if (it.length <= 80) onUpdate(assistant.copy(userInteractionSuffix = it)) },
        )
        Text("AI 对你的互动", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = assistant.assistantInteractionAction, label = { Text("动作，例如：拍了拍") }, singleLine = true,
            onValueChange = { if (it.length <= 20) onUpdate(assistant.copy(assistantInteractionAction = it)) },
        )
        OutlinedTextField(
            value = assistant.assistantInteractionSuffix, label = { Text("后缀，例如：的肩膀，并抱了抱你") },
            onValueChange = { if (it.length <= 80) onUpdate(assistant.copy(assistantInteractionSuffix = it)) },
        )
    }
}
