package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import me.rerere.rikkahub.ui.theme.MaterialAlertDialog as AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.ContextCompaction

@Composable
internal fun ConversationSummaryDivider(valid: Boolean?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f))
        TextButton(onClick = onClick) {
            Text(
                if (valid == false) "历史摘要已失效 · 点击查看" else "以上对话已总结 · 点击查看",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
internal fun ConversationSummaryDialog(
    summary: ContextCompaction,
    canEdit: Boolean?,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> Result<Unit>,
) {
    var editing by rememberSaveable(summary) { mutableStateOf(false) }
    // Keep selection and IME composition locally; do not write to the database on every keystroke.
    var draft by rememberSaveable(summary, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(summary.summary))
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("对话摘要") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "覆盖前 ${summary.coveredMessageCount} 条消息，聊天原文完整保留。手动保存不会调用模型。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (canEdit == null) {
                    Text("正在检查摘要…", style = MaterialTheme.typography.bodySmall)
                } else if (!canEdit) {
                    Text(
                        "原对话或摘要已变化，此处仅供查看；请重新打开最新摘要或重新压缩。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (editing) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("摘要内容") },
                        minLines = 5,
                        maxLines = 12,
                        readOnly = saving || canEdit != true,
                    )
                } else {
                    SelectionContainer { Text(summary.summary, style = MaterialTheme.typography.bodyMedium) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (editing) {
                TextButton(
                    enabled = canEdit == true && !saving && draft.text.isNotBlank() && draft.text.trim() != summary.summary,
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                onSave(draft.text).fold(
                                    onSuccess = { onDismiss() },
                                    onFailure = { error = it.message ?: "保存失败，修改尚未保存" },
                                )
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) { Text(if (saving) "保存中…" else "保存") }
            } else {
                TextButton(enabled = canEdit == true, onClick = { editing = true }) { Text("编辑") }
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(if (editing) "取消" else "关闭") }
        },
    )
}
