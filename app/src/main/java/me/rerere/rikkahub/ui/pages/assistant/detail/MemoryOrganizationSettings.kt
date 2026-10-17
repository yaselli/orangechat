package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.memory.MemoryEvidence
import me.rerere.rikkahub.data.memory.MemoryOrganizationService
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject

@Composable
internal fun MemoryOrganizationSettings(assistant: Assistant, settings: Settings, onUpdate: (Assistant) -> Unit) {
    val service = koinInject<MemoryOrganizationService>()
    val latestFlow = remember(assistant.id) { service.latest(assistant.id) }
    val latest by latestFlow.collectAsStateWithLifecycle(initialValue = null)
    val model = assistant.memoryOrganizationModelId?.let(settings::findModelById)
    val configured = model?.type == ModelType.CHAT && model.findProvider(settings.providers)?.enabled == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("自动整理记忆", style = MaterialTheme.typography.titleMedium)
        Text("从开启后的聊天中挑选值得长期记住的事，附上原话出处；不需要先压缩对话。" +
            "请选择便宜的文字模型，按服务商计费。", style = MaterialTheme.typography.bodySmall)
        ModelSelector(
            modelId = assistant.memoryOrganizationModelId,
            providers = settings.providers.filter { it.enabled },
            type = ModelType.CHAT,
            onSelect = { onUpdate(assistant.copy(memoryOrganizationModelId = it.id)) },
        )
        if (assistant.memoryOrganizationModelId != null && !configured) {
            Text("整理模型当前不可用，请重新选择已启用的文字模型。", style = MaterialTheme.typography.bodySmall)
        }
        CardGroup {
            item(
                headlineContent = { Text("后台自动整理") },
                supportingContent = { Text("回复结束后积累一批再整理，每批最多一次模型请求；失败不自动重试。") },
                trailingContent = {
                    Switch(
                        checked = assistant.memoryAutoOrganizeEnabled,
                        enabled = assistant.memoryAutoOrganizeEnabled || (assistant.enableMemory && configured),
                        onCheckedChange = { enabled ->
                            onUpdate(assistant.copy(
                                memoryAutoOrganizeEnabled = enabled,
                                memoryOrganizationSinceMillis = if (enabled) System.currentTimeMillis()
                                    else assistant.memoryOrganizationSinceMillis,
                            ))
                        },
                    )
                },
            )
        }
        if (assistant.memoryAutoOrganizeEnabled) {
            Text("每个对话积累多少条用户消息整理一批", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 20, 30).forEach { interval ->
                    FilterChip(
                        selected = assistant.memoryOrganizationInterval == interval,
                        onClick = { onUpdate(assistant.copy(memoryOrganizationInterval = interval)) },
                        label = { Text("$interval 条") },
                    )
                }
            }
            Text("仅在应用仍运行时执行，不会定时唤醒手机或补扫开启前的旧对话。" +
                "自动整理只新增有原话依据的记忆，不自动删除或改写你已有的记忆。" +
                "超长原话会跳过，不截断后硬存。", style = MaterialTheme.typography.bodySmall)
        }
        latest?.let { batch ->
            Text("最近整理：${batch.detail}", style = MaterialTheme.typography.bodySmall)
        } ?: Text("尚未整理。开启后积累新聊天，达到条数再自动处理。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun MemoryOriginalButton(id: Int, assistant: Assistant) {
    val service = koinInject<MemoryOrganizationService>()
    val scope = if (assistant.useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else assistant.id.toString()
    var showOriginalDialog by remember(id, scope) { mutableStateOf(false) }
    if (showOriginalDialog) {
        var result by remember(id, scope) { mutableStateOf<Pair<MemoryEvidence, Boolean>?>(null) }
        var loaded by remember(id, scope) { mutableStateOf(false) }
        LaunchedEffect(id, scope) {
            try {
                result = service.evidence(id, scope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                result = null
            } finally {
                loaded = true
            }
        }
        AlertDialog(
            onDismissRequest = { showOriginalDialog = false },
            title = { Text("记忆原话") },
            text = {
                SelectionContainer {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val value = result
                        if (!loaded) {
                            Text("正在读取…")
                        } else if (value == null) {
                            Text("没有可验证的自动整理原话，或这条记忆已被修改。")
                        } else {
                            val (evidence, valid) = value
                            Text(if (evidence.role == "user") "她的原话" else "我的原话")
                            Text(evidence.sourceCreatedAt)
                            Text(evidence.quote)
                            val validityText = if (valid) {
                                "原消息仍存在，内容一致。"
                            } else {
                                "以上是保存时的原话；原消息已修改、删除或切换分支。"
                            }
                            Text(validityText)
                            Text("会话 ${evidence.conversationId.take(8)} · 消息 ${evidence.messageId.take(8)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showOriginalDialog = false }) { Text("关闭") } },
        )
    }
    TextButton(onClick = { showOriginalDialog = true }) { Text("查看原话") }
}
