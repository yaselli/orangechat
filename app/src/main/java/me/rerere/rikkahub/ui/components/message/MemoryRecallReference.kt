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
import me.rerere.rikkahub.data.memory.MemoryRerankState
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
                    if (trace.state !in setOf(MemoryRecallState.DISABLED, MemoryRecallState.LEGACY)) {
                        Text("候选：记忆 ${trace.diagnostics.longTermCandidates} / 独立历史 ${trace.diagnostics.historyCandidates}" +
                            " · 筛选后 ${trace.diagnostics.selectedCount} · 最终附带 ${trace.diagnostics.injectedCount}",
                            style = MaterialTheme.typography.bodySmall)
                        if (trace.diagnostics.budgetDropped > 0) Text("${trace.diagnostics.budgetDropped} 组因记忆与完整引文超过总预算而未发送。")
                        if (trace.diagnostics.staleDropped > 0) Text("${trace.diagnostics.staleDropped} 条在发送前来源失效。")
                    }
                    if (trace.diagnostics.deadlineExceeded) {
                        Text("记忆准备达到4秒总预算，已取消本轮检索；本轮未附带召回记忆，也未自动重试。")
                    }
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
                            trace.diagnostics.graph ?: when (trace.diagnostics.vector) {
                                MemoryVectorState.NOT_CONFIGURED -> "未配置可用嵌入模型，使用关键词检索。"
                                MemoryVectorState.NO_INDEX -> "没有可用向量索引，使用关键词检索。"
                                MemoryVectorState.SUCCESS -> "向量查询成功，已与关键词结果融合；最终引用见下方。"
                                MemoryVectorState.CACHED -> "复用了查询向量缓存，并与关键词候选融合；最终引用见下方。"
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
                    val timings = trace.diagnostics.timings
                    timings.totalMillis?.let { total ->
                        fun seconds(millis: Long) = String.format(java.util.Locale.ROOT, "%.2f秒", millis / 1000.0)
                        Text("首次记忆准备共 ${seconds(total)}（不含聊天模型出字；重试会复用首次记录）",
                            style = MaterialTheme.typography.bodySmall)
                        Text(listOfNotNull(
                            timings.keywordsMillis?.let { "关键词 ${seconds(it)}" },
                            timings.vectorMillis?.let { "向量 ${seconds(it)}" },
                            timings.rerankMillis?.let { "重排 ${seconds(it)}" },
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        timings.embeddingMillis?.let {
                            Text("向量阶段中，查询嵌入接口耗时 ${seconds(it)}。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (trace.diagnostics.vector in setOf(MemoryVectorState.TIMEOUT, MemoryVectorState.FAILED)) {
                            trace.diagnostics.vectorPhase?.let {
                                Text("向量中断位置：$it", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (trace.diagnostics.rerank != MemoryRerankState.DISABLED) {
                        Text("长期候选 ${trace.diagnostics.longTermCandidates} · 短期候选 ${trace.diagnostics.historyCandidates} · 合并送审 ${trace.diagnostics.candidates.size}")
                        Text(when (trace.diagnostics.rerank) {
                            MemoryRerankState.NOT_CONFIGURED -> "未配置可用重排模型，本轮未附带候选。"
                            MemoryRerankState.NO_CANDIDATES -> "两路都没有可送审候选，未请求重排。"
                            MemoryRerankState.SUCCESS -> "重排完成；达到门槛的片段还需通过数量、长度和来源校验。"
                            MemoryRerankState.TIMEOUT -> "重排超时，未自动重试，本轮未附带候选。"
                            MemoryRerankState.FAILED -> "重排失败或返回数据无效，未自动重试，本轮未附带候选。"
                            MemoryRerankState.DISABLED -> "重排未开启。"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                    if (trace.diagnostics.candidates.isNotEmpty()) {
                        trace.diagnostics.candidates.forEachIndexed { index, hit ->
                            Text("候选 ${index + 1} · " + (if (hit.history == null) "长期 #${hit.memoryId}" else "短期 ${hit.history.role}") +
                                " · 分数 ${hit.score?.let { String.format(java.util.Locale.ROOT, "%.3f", it) } ?: "未返回"}" +
                                if (hit.kept) " · 筛选保留" else " · 未保留", style = MaterialTheme.typography.labelSmall)
                            Text(hit.text, style = MaterialTheme.typography.bodySmall)
                            hit.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
                        if (excerpt.history == null) {
                            val evidence = excerpt.evidence
                            if (evidence != null) {
                                Text("对应原话：${evidence.quote}", style = MaterialTheme.typography.bodyMedium)
                                Text("${evidence.source.role} · ${evidence.source.createdAt.replace('T', ' ')}" +
                                    " · 消息 ${evidence.source.messageId.take(8)}", style = MaterialTheme.typography.bodySmall)
                            } else Text(if (excerpt.evidenceState == me.rerere.rikkahub.data.memory.MemoryEvidenceState.STALE)
                                "原话依据已失效，本轮未附带引文。" else "没有可验证的对应原话，未补配其他历史内容。",
                                style = MaterialTheme.typography.bodySmall)
                        } else Text("独立历史原文，未作为其他记忆的出处。", style = MaterialTheme.typography.bodySmall)
                        excerpt.graphRelation?.let { Text("图谱依据：$it", style = MaterialTheme.typography.bodySmall) }
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
