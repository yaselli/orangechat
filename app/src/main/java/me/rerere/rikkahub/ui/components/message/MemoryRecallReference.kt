package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.memory.MemoryRecallState
import me.rerere.rikkahub.data.memory.MemoryRecallTrace
import me.rerere.rikkahub.data.memory.MemoryVectorState
import me.rerere.rikkahub.data.memory.memoryRecallTrace
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun MemoryRecallReferenceButton(message: UIMessage) {
    if (message.role != MessageRole.ASSISTANT) return
    val trace = remember(message.localMetadata) { message.memoryRecallTrace() } ?: return
    var opened by remember(message.id) { mutableStateOf<MemoryRecallTrace?>(null) }
    TextButton(
        onClick = { opened = trace },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Text(recallLabel(trace), style = MaterialTheme.typography.labelSmall)
    }
    opened?.let { snapshot -> MemoryRecallReferenceDialog(snapshot) { opened = null } }
}

private fun recallLabel(trace: MemoryRecallTrace): String = when (trace.state) {
    MemoryRecallState.USED -> "参考记忆 · ${trace.excerpts.size} 个片段" + if (trace.degraded) " · 部分检索失败" else ""
    MemoryRecallState.NO_MATCH -> "未引用记忆 · 没有命中"
    MemoryRecallState.FAILED -> "记忆查询失败 · 点击查看"
    MemoryRecallState.DISABLED -> "记忆召回未开启"
    MemoryRecallState.LEGACY -> "整库记忆模式"
    MemoryRecallState.NOT_APPLIED -> "本轮未注入记忆"
    MemoryRecallState.STALE -> "未引用记忆 · 来源已变化"
}

@Composable
private fun MemoryRecallReferenceDialog(trace: MemoryRecallTrace, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本轮记忆引用") },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(recallLabel(trace), style = MaterialTheme.typography.titleSmall)
                    when (trace.state) {
                        MemoryRecallState.DISABLED -> Text("该请求没有开启内置记忆功能。")
                        MemoryRecallState.LEGACY -> Text("该请求使用旧的整库记忆模式，未记录按需召回片段。")
                        MemoryRecallState.NOT_APPLIED -> Text("没有找到可注入的对应用户消息，本次没有附加召回片段。")
                        MemoryRecallState.STALE -> Text("候选记忆或历史在发送前发生变化，没有发送失效片段。")
                        MemoryRecallState.FAILED -> Text("检索、快照读取或来源校验未正常完成；不能据此判断没有相关记忆。")
                        MemoryRecallState.NO_MATCH -> Text("本次没有附加相关记忆片段。")
                        MemoryRecallState.USED -> Text("以下是这次请求实际附带的片段，不表示回答的每句话都引用了它们。")
                    }
                    if (trace.state !in setOf(MemoryRecallState.DISABLED, MemoryRecallState.LEGACY)) {
                        Text(
                            when (trace.diagnostics.vector) {
                                MemoryVectorState.NOT_CONFIGURED -> "未配置可用嵌入模型，使用关键词检索。"
                                MemoryVectorState.NO_INDEX -> "没有可用向量索引，使用关键词检索。"
                                MemoryVectorState.SUCCESS -> "向量查询成功，已与关键词结果融合。"
                                MemoryVectorState.CACHED -> "复用了本地查询向量缓存，已与关键词结果融合。"
                                MemoryVectorState.TIMEOUT -> "向量检索超时，使用可用的关键词结果；未自动重试。"
                                MemoryVectorState.FAILED -> "向量检索失败，使用可用的关键词结果；未自动重试。"
                                MemoryVectorState.NO_QUERY -> "没有可用于搜索的文字。"
                                MemoryVectorState.UNKNOWN -> "原查询状态未记录或查询尚未完成，无法确认向量是否成功。"
                            }, style = MaterialTheme.typography.bodySmall,
                        )
                        if (trace.diagnostics.historyKeywordsFailed) {
                            Text("历史关键词查询失败，仅使用其他可用结果。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    trace.excerpts.forEachIndexed { index, excerpt ->
                        HorizontalDivider()
                        val history = excerpt.history
                        Text(
                            "${index + 1}. " + if (history == null) {
                                "长期记忆 #${excerpt.memoryId} · 版本 ${excerpt.revision}"
                            } else {
                                "历史聊天 · " + if (history.role.equals("user", true)) "用户原文" else "AI 原文"
                            }, style = MaterialTheme.typography.titleSmall,
                        )
                        if (history != null) {
                            Text(history.createdAt.replace('T', ' '), style = MaterialTheme.typography.bodySmall)
                            Text("会话 ${history.conversationId.take(8)} · 消息 ${history.messageId.take(8)}",
                                style = MaterialTheme.typography.bodySmall)
                        } else {
                            val date = excerpt.createdAtMillis?.let { millis ->
                                runCatching {
                                    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                                }.getOrNull()
                            }
                            Text(date ?: "原记忆未记录创建日期", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(excerpt.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("仅记录按需召回片段，不统计人设、原上下文或记忆工具的读取结果。",
                        style = MaterialTheme.typography.bodySmall)
                    if (trace.excerpts.isNotEmpty()) {
                        Text("这是当时发送的引用记录；后续编辑记忆不会改写这次记录。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
