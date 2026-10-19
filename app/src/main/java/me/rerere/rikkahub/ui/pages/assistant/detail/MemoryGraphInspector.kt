package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.memory.MemoryGraphCandidate
import me.rerere.rikkahub.data.memory.MemoryGraphInspectionEntry
import me.rerere.rikkahub.data.memory.MemoryGraphService
import me.rerere.rikkahub.data.memory.selectMemoryGraph
import me.rerere.rikkahub.data.model.Assistant
import org.koin.compose.koinInject

@Composable
internal fun MemoryGraphInspectorButton(assistant: Assistant) {
    val service = koinInject<MemoryGraphService>()
    var opened by remember(assistant) { mutableStateOf(false) }
    TextButton(onClick = { opened = true }) { Text("查看图谱 / 本地定位测试") }
    if (!opened) return
    var entries by remember { mutableStateOf<List<MemoryGraphInspectionEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Int>?>(null) }
    LaunchedEffect(assistant) {
        try { entries = service.inspect(assistant)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { error = "读取图谱失败；请关闭后重试。" }
    }
    AlertDialog(
        onDismissRequest = { opened = false },
        title = { Text("已存图谱") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    val rows = entries
                    if (error != null) Text(error.orEmpty())
                    else if (rows == null) Text("正在校验图谱与原话…")
                    else {
                        Text("记忆 ${rows.size} 条 · 可用关系 ${rows.count { it.usable }} 条 · " +
                            "其余 ${rows.count { !it.usable }} 条", style = MaterialTheme.typography.bodySmall)
                        Text("下方逐条显示关系、别名、依据和停用原因。已处理不等于有可用关系。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = query, onValueChange = { query = it.take(2000); hits = null },
                            label = { Text("输入一句话测试名称 / 别名定位") }, modifier = Modifier.fillMaxWidth(),
                            minLines = 2, maxLines = 4)
                        Text("只测本地入口，不请求模型、不产生费用；不包含聊天时的向量语义检索。" +
                            "可分行补充上下文：当前用户句在前，较新的对话在后。", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = entries != null && query.isNotBlank(), onClick = {
                            val candidates = entries.orEmpty().filter { it.usable }.mapNotNull { row ->
                                row.relation?.let { MemoryGraphCandidate(row.memoryId, it, row.createdAt) }
                            }
                            hits = selectMemoryGraph(query, candidates).map { it.memoryId }
                        }) { Text("测试本地定位") }
                        hits?.let { ids ->
                            Text(if (ids.isEmpty()) "本地没有入口；聊天时仍会尝试语义定位，具体结果见回复下的引用记录。"
                                else "本地选中 ${ids.size} 条关系：${ids.joinToString { "#$it" }}")
                            TextButton(onClick = { hits = null }) { Text("查看全部记录") }
                        }
                    }
                }
                val visible = entries.orEmpty().filter { hits == null || it.memoryId in hits.orEmpty() }
                items(visible, key = { it.memoryId }) { row ->
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("#${row.memoryId} · ${row.reason}", style = MaterialTheme.typography.titleSmall)
                        row.relation?.let { relation ->
                            Text(relation.label())
                            listOf("主体" to relation.subject, "对象" to relation.target, "主题" to relation.topic)
                                .forEach { (label, node) ->
                                    Text("$label：${node.name}（${node.type}） · 别名：" +
                                        node.aliases.joinToString("、").ifBlank { "无" },
                                        style = MaterialTheme.typography.bodySmall)
                                }
                        }
                        Text("记忆：${row.content}", style = MaterialTheme.typography.bodySmall)
                        if (row.quote.isNotBlank()) Text("建图依据：${row.quote}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { opened = false }) { Text("关闭") } },
    )
}
