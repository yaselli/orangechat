package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.memory.MemoryAutoIndexService
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject

@Composable
internal fun MemoryRecallAdvancedSettings(assistant: Assistant, settings: Settings, onUpdate: (Assistant) -> Unit) {
    val auto = koinInject<MemoryAutoIndexService>()
    val latestFlow = remember(assistant.id) { auto.latest(assistant.id) }
    val latest by latestFlow.collectAsStateWithLifecycle(initialValue = null)
    var dailyUsed by remember(assistant.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(assistant.id, latest) {
        try { dailyUsed = auto.dailyUsed(assistant.id)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { dailyUsed = null }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardGroup {
            item(headlineContent = { Text("重排筛选") }, supportingContent = {
                Text("默认只筛选长期记忆，命中后自动附上有效出处；手动开启旧模式时才加入独立历史候选。每轮最多一次 /rerank 请求，按服务商计费；失败不重试，本轮不附带未筛选结果。")
            }, trailingContent = { Switch(assistant.memoryRerankEnabled,
                onCheckedChange = { onUpdate(assistant.copy(memoryRerankEnabled = it)) }) })
        }
        if (assistant.memoryRerankEnabled) {
            Text("使用硅基流动/Jina 格式的 /rerank 接口。先在服务商模型设置中把所用模型类型设为“重排”，再选择；聊天模型不能代替重排模型。",
                style = MaterialTheme.typography.bodySmall)
            ModelSelector(modelId = assistant.memoryRerankModelId,
                providers = settings.providers.filter { it.enabled && it is ProviderSetting.OpenAI },
                type = ModelType.RERANK, onSelect = { onUpdate(assistant.copy(memoryRerankModelId = it.id)) })
            Text("相关性门槛：${assistant.memoryRerankThreshold}（分数不是置信概率，不同模型需要实际测试）",
                style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.2, 0.5, 0.7).forEach { value -> FilterChip(assistant.memoryRerankThreshold == value,
                    onClick = { onUpdate(assistant.copy(memoryRerankThreshold = value)) }, label = { Text("$value") }) }
            }
            Text("重排后最多引用几个片段（仍受总长度预算限制）", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..4).forEach { value -> FilterChip(assistant.memoryRerankLimit == value,
                    onClick = { onUpdate(assistant.copy(memoryRerankLimit = value)) }, label = { Text("$value 个") }) }
            }
        }
        CardGroup {
            item(headlineContent = { Text("自动更新向量索引") }, supportingContent = {
                Text("默认关闭。开启后只处理开启以来创建或修改的长期记忆（旧模式额外处理新原文）；每次回复完成最多一批32个片段、一次嵌入请求，有费用。应用关闭时不运行，不自动补扫旧聊天。")
            }, trailingContent = { Switch(assistant.memoryAutoIndexEnabled,
                enabled = assistant.memoryAutoIndexEnabled || assistant.memoryEmbeddingModelId != null,
                onCheckedChange = { enabled -> onUpdate(assistant.copy(memoryAutoIndexEnabled = enabled,
                    memoryAutoIndexSinceMillis = if (enabled) System.currentTimeMillis() else assistant.memoryAutoIndexSinceMillis)) }) })
        }
        if (assistant.memoryAutoIndexEnabled) {
            Text("每天自动请求上限；失败和中断也计入，旧记录可手动更新", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 20, 50).forEach { value -> FilterChip(assistant.memoryAutoIndexDailyLimit == value,
                    onClick = { onUpdate(assistant.copy(memoryAutoIndexDailyLimit = value)) }, label = { Text("$value 批") }) }
            }
            dailyUsed?.let { Text("今日自动索引：$it / ${assistant.memoryAutoIndexDailyLimit} 批",
                style = MaterialTheme.typography.bodySmall) }
            Text(latest?.detail ?: "尚无自动索引记录。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
