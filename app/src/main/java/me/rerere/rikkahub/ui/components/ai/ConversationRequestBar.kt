package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.db.dao.ContextUsage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation

@Composable
internal fun ConversationRequestBar(
    conversation: Conversation,
    assistant: Assistant,
    model: Model?,
    usage: ContextUsage?,
    loading: Boolean,
    onSave: (Float?, Float?, Int?, Int) -> Unit,
    onModelLimit: (Model, Int?) -> Unit,
    onSync: () -> Unit,
) {
    var show by remember(conversation.id) { mutableStateOf(false) }
    val input = usage?.takeIf { it.modelId == model?.id?.toString() }?.inputTokens
    val limit = model?.contextWindow
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (input == null) "上下文：等待接口用量" else
                "上次输入 $input" + if (limit == null) " token" else " / $limit token",
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = if (input != null && limit != null && input >= limit) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(enabled = !loading, onClick = { show = true }) { Text("会话设置") }
    }
    if (!show) return
    var temperature by remember { mutableStateOf(assistant.temperature?.toString().orEmpty()) }
    var topP by remember { mutableStateOf(assistant.topP?.toString().orEmpty()) }
    var output by remember { mutableStateOf(assistant.maxTokens?.toString().orEmpty()) }
    var history by remember { mutableStateOf(assistant.contextMessageSize.toString()) }
    var window by remember { mutableStateOf(limit?.toString().orEmpty()) }
    val valid = validOptionalFloat(temperature, 0f..2f) && validOptionalFloat(topP, 0f..1f) &&
        validOptionalInt(output) && validOptionalInt(window) && history.toIntOrNull()?.let { it in 0..100_000 } == true &&
        (output.toIntOrNull() == null || window.toIntOrNull() == null || output.toInt() < window.toInt())
    AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("当前会话的请求设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (conversation.config == null) "保存后固定到此会话" else "以下参数仅影响当前会话")
                Text("模型、思考、搜索、MCP 和技能沿用输入栏选择；角色的记忆与外观保持共用。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(temperature, { temperature = it }, label = { Text("温度 0–2，留空使用接口默认") })
                OutlinedTextField(topP, { topP = it }, label = { Text("Top P 0–1，留空使用接口默认") })
                OutlinedTextField(output, { output = it }, label = { Text("最大输出 token，留空使用接口默认") })
                OutlinedTextField(history, { history = it }, label = { Text("保留消息数，0 为全部") })
                OutlinedTextField(window, { window = it }, enabled = model != null,
                    label = { Text("模型上下文上限，留空为未知") })
                Text("模型上限由该模型的所有会话共用，仅用于显示。用量取自上一次接口返回，" +
                    "不是下一次请求的精确预算，也不会触发自动压缩。", style = MaterialTheme.typography.bodySmall)
                if (!valid) Text("请检查数字范围；最大输出应小于模型上下文上限。", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { onSync(); show = false }) { Text("同步角色默认设置") }
            }
        },
        confirmButton = { TextButton(enabled = valid && !loading, onClick = {
            onSave(temperature.toFloatOrNull(), topP.toFloatOrNull(), output.toIntOrNull(), history.toInt())
            model?.let { onModelLimit(it, window.toIntOrNull()) }
            show = false
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { show = false }) { Text("取消") } },
    )
}

internal fun validOptionalFloat(text: String, range: ClosedFloatingPointRange<Float>): Boolean =
    text.isBlank() || text.toFloatOrNull()?.let { it.isFinite() && it in range } == true

internal fun validOptionalInt(text: String): Boolean = text.isBlank() || text.toIntOrNull()?.let { it > 0 } == true
