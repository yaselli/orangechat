package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.service.BlockedChatState
import kotlin.math.roundToInt

@Composable
internal fun BlockedChatMenu(state: BlockedChatState, onSetBlocked: (Boolean, Int) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableStateOf(5) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "聊天管理" },
        ) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (state.blocked) "解除拉黑" else "拉黑对方") },
                onClick = {
                    expanded = false
                    if (state.blocked) onSetBlocked(false, state.limit) else confirming = true
                },
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("拉黑当前聊天的对方？") },
            text = {
                Column {
                    Text("你将暂时不能发送消息，但仍能看到对方的回复。应用会告知对方已被拉黑，并自动续接回复。")
                    Text("每收到 $limit 次完整回复就暂停，提醒你选择是否解除拉黑。续聊会使用模型额度。")
                    Slider(
                        value = limit.toFloat(),
                        onValueChange = { limit = it.roundToInt() },
                        valueRange = 1f..20f,
                        steps = 18,
                        modifier = Modifier.semantics { contentDescription = "每批回复次数" },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onSetBlocked(true, limit)
                }) { Text("拉黑并开始") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } },
        )
    }
}

@Composable
internal fun BlockedChatBar(
    state: BlockedChatState,
    onUnblock: () -> Unit,
    onContinue: () -> Unit,
    onPause: () -> Unit,
) {
    var showReminder by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.reachedLimit, state.running) {
        if (state.reachedLimit && !state.running) showReminder = true
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("已拉黑 · 本批回复 ${state.replies}/${state.limit}", style = MaterialTheme.typography.titleSmall)
            Text(
                if (state.running) "你仍能看回复，暂时不能发送消息。" else "自动回复已暂停，你可以解除拉黑或继续。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (state.running) {
                    TextButton(onClick = onPause) { Text("暂停") }
                } else {
                    TextButton(onClick = onContinue) { Text(if (state.reachedLimit) "再继续一批" else "继续") }
                }
                TextButton(onClick = onUnblock) { Text("解除拉黑") }
            }
        }
    }
    if (showReminder) {
        AlertDialog(
            onDismissRequest = { showReminder = false },
            title = { Text("要解除拉黑吗？") },
            text = { Text("已收到 ${state.limit} 次完整回复，自动续聊已暂停。由你决定是否解除拉黑。") },
            confirmButton = {
                TextButton(onClick = { showReminder = false; onUnblock() }) { Text("解除拉黑") }
            },
            dismissButton = {
                TextButton(onClick = { showReminder = false; onContinue() }) { Text("保持拉黑，再继续一批") }
            },
        )
    }
}
