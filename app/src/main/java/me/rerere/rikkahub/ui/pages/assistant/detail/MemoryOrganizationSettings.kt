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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.memory.MemoryEvidence
import me.rerere.rikkahub.data.memory.MemoryOrganizationService
import me.rerere.rikkahub.data.memory.MemoryGraphService
import me.rerere.rikkahub.data.memory.memoryGraphBuildFeedback
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject

@Composable
internal fun MemoryOrganizationSettings(assistant: Assistant, settings: Settings, onUpdate: (Assistant) -> Unit) {
    val service = koinInject<MemoryOrganizationService>()
    val graph = koinInject<MemoryGraphService>()
    val coroutineScope = rememberCoroutineScope()
    var graphStatus by remember(assistant.id, assistant.useGlobalMemory) { mutableStateOf<Pair<Int, Int>?>(null) }
    var graphBusy by remember { mutableStateOf(false) }
    var graphMessage by remember { mutableStateOf<String?>(null) }
    var confirmGraph by remember { mutableStateOf(false) }
    val latestFlow = remember(assistant.id) { service.latest(assistant.id) }
    val latest by latestFlow.collectAsStateWithLifecycle(initialValue = null)
    val model = assistant.memoryOrganizationModelId?.let(settings::findModelById)
    val configured = model?.type == ModelType.CHAT && model.findProvider(settings.providers)?.enabled == true
    LaunchedEffect(assistant.id, assistant.memoryGraphEnabled, assistant.useGlobalMemory, latest) {
        graphStatus = if (assistant.memoryGraphEnabled) try {
            graph.status(assistant)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { null } else null
    }
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
        CardGroup {
            item(
                headlineContent = { Text("图谱筛选召回") },
                supportingContent = { Text("结合近期双方对话定位实体、主题和别名，沿关系找记忆与原话；最多4条关系。" +
                    "本地未定位到时，以向量语义相似度寻找图谱入口。需同时开启按需召回。") },
                trailingContent = {
                    Switch(checked = assistant.memoryGraphEnabled, enabled = !graphBusy,
                        onCheckedChange = { onUpdate(assistant.copy(memoryGraphEnabled = it)) })
                },
            )
        }
        if (assistant.memoryGraphEnabled) {
            Text("新自动记忆在同一次整理请求里提取关系，输出上限由2048提高到4096 token，费用按实际用量。" +
                "聊天优先本地定位；未命中且有可用向量索引时，最多一次查询嵌入，会增加费用与等待，不调用重排。" +
                "语义入口暂用最高分且余弦相似度≥0.55的一条记忆，沿实体最多取两条关系；分数不是置信概率，效果随嵌入模型变化。",
                style = MaterialTheme.typography.bodySmall)
            graphStatus?.let { Text("已处理 ${it.first} 条记忆，待建图 ${it.second} 条；无明确关系的记忆不会硬建图。",
                style = MaterialTheme.typography.bodySmall) }
            TextButton(enabled = !graphBusy && configured && assistant.enableMemory,
                onClick = { confirmGraph = true }) { Text(if (graphBusy) "正在建图…" else "为已有记忆建图") }
            MemoryGraphInspectorButton(assistant)
            graphMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (confirmGraph) {
        AlertDialog(
            onDismissRequest = { confirmGraph = false },
            title = { Text("为已有记忆建图") },
            text = { Text("使用当前整理模型，本次最多处理8条未建图的记忆，调用一次并单独计费。" +
                "单条超过2000字跳过，不自动继续或重试，不改写记忆。旧记忆只有记忆正文出处，不补造用户原话。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmGraph = false
                    graphBusy = true
                    coroutineScope.launch {
                        try {
                            val feedback = memoryGraphBuildFeedback(
                                build = { graph.build(assistant) }, refresh = { graph.status(assistant) },
                            )
                            graphMessage = feedback.message
                            graphStatus = feedback.status
                        } finally { graphBusy = false }
                    }
                }) { Text("确认建图") }
            },
            dismissButton = { TextButton(onClick = { confirmGraph = false }) { Text("取消") } },
        )
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
