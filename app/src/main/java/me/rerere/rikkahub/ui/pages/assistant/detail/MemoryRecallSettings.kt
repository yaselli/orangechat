package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.memory.MemoryRecallService
import me.rerere.rikkahub.data.memory.memoryIndexFailureReason
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

@Composable
internal fun MemoryRecallSettings(assistant: Assistant, settings: Settings, onUpdate: (Assistant) -> Unit) {
    val service = koinInject<MemoryRecallService>()
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember(assistant.id, assistant.useGlobalMemory, assistant.memoryEmbeddingModelId, assistant.memoryIndependentHistoryRecallEnabled) {
        mutableStateOf("")
    }
    var plan by remember { mutableStateOf<MemoryRecallService.IndexPlan?>(null) }
    // Freeze the exact config that the user approved; a changed scope/model needs a fresh plan.
    var plannedAssistant by remember { mutableStateOf(assistant) }
    var plannedSettings by remember { mutableStateOf(settings) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardGroup {
            item(
                headlineContent = { Text("按需召回记忆（测试）") },
                supportingContent = { Text("默认只搜索已保存记忆，命中后携带对应原话；无相关结果可以不注入。开启后不再整库注入。") },
                trailingContent = {
                    Switch(
                        checked = assistant.memoryRecallEnabled,
                        enabled = assistant.enableMemory && !busy,
                        onCheckedChange = { onUpdate(assistant.copy(memoryRecallEnabled = it)) },
                    )
                },
            )
        }
        if (assistant.memoryRecallEnabled && assistant.enableMemory) {
            CardGroup {
                item(
                    headlineContent = { Text("独立召回历史原文（旧模式）") },
                    supportingContent = {
                        Text("默认关闭，升级后也需重新手动开启。开启会额外注入未关联记忆的历史原文，可能带来无关内容。")
                    },
                    trailingContent = {
                        Switch(
                            checked = assistant.memoryIndependentHistoryRecallEnabled, enabled = !busy,
                            onCheckedChange = { onUpdate(assistant.copy(memoryIndependentHistoryRecallEnabled = it)) },
                        )
                    },
                )
            }
            if (assistant.memoryIndependentHistoryRecallEnabled) TextButton(enabled = !busy, onClick = {
                val target = assistant
                busy = true
                job = scope.launch {
                    try {
                        val count = service.refreshHistoryKeywords(target)
                        status = "已更新 $count 条历史的关键词索引，没有调用 API。"
                    } catch (cancelled: CancellationException) {
                        status = "已取消本地索引更新。"
                        throw cancelled
                    } catch (_: Exception) {
                        status = "本地历史索引更新失败，原聊天保留。"
                    } finally { busy = false }
                }
            }) { Text("更新历史关键词索引（本地）") }
            Card(colors = CustomColors.cardColorsOnSurfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("嵌入模型", style = MaterialTheme.typography.titleSmall)
                    Text("未配置时使用主题关键词召回。纯语义候选需要重排筛选；向量支持 OpenAI 兼容接口。",
                        style = MaterialTheme.typography.bodySmall)
                    if (!busy) ModelSelector(
                        modelId = assistant.memoryEmbeddingModelId,
                        providers = settings.providers.filter { it.enabled && it is ProviderSetting.OpenAI },
                        type = ModelType.EMBEDDING,
                        onSelect = { onUpdate(assistant.copy(memoryEmbeddingModelId = it.id)) },
                    )
                    Text("建立向量索引会发送记忆正文；开启历史检索后，也包含当前助手的历史普通文字。" +
                        "聊天每轮最多共用一次查询嵌入，失败不重试。",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy && assistant.memoryEmbeddingModelId != null, onClick = {
                        plannedAssistant = assistant
                        plannedSettings = settings
                        busy = true
                        job = scope.launch {
                            try {
                                plan = service.indexPlan(plannedAssistant, plannedSettings)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                status = "无法准备索引：${memoryIndexFailureReason(failure)}"
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("建立／更新向量索引") }
                    if (busy) TextButton(onClick = { job?.cancel() }) { Text("取消本次任务") }
                    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
            MemoryRecallAdvancedSettings(assistant, settings, onUpdate)
        }
    }
    plan?.let { currentPlan ->
        AlertDialog(
            onDismissRequest = { plan = null },
            title = { Text("建立记忆向量索引") },
            text = { Text("本批有 ${currentPlan.pendingChunks} 个新片段，最多调用嵌入接口 ${currentPlan.calls} 次。" +
                (if (currentPlan.hasMore) "剩余片段可在完成本批后再次手动更新。" else "") +
                "按服务商计费，已完成的片段会保留，失败或取消后不会自动重试。") },
            dismissButton = { TextButton(onClick = { plan = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    plan = null
                    busy = true
                    status = "正在建立索引…"
                    job = scope.launch {
                        try {
                            val count = service.buildIndex(
                                plannedAssistant, plannedSettings, maxChunks = currentPlan.pendingChunks,
                            )
                            status = "索引已更新，本次处理 $count 个片段。"
                        } catch (cancelled: CancellationException) {
                            status = "已取消，完成的索引已保留。"
                            throw cancelled
                        } catch (failure: Exception) {
                            status = "本次索引未完成：${memoryIndexFailureReason(failure)}\n" +
                                "已完成片段保留，原聊天和记忆不变；不会自动重试。"
                        } finally { busy = false }
                    }
                }) { Text("开始") }
            },
        )
    }
}
