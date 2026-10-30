package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import me.rerere.rikkahub.ui.theme.MaterialAlertDialog as AlertDialog
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
import me.rerere.rikkahub.data.ai.requestInputProfile
import java.util.Locale

@Composable
internal fun RequestInputProfileButton(message: UIMessage) {
    if (message.role != MessageRole.ASSISTANT) return
    val profile = remember(message.localMetadata) { message.requestInputProfile() } ?: return
    var opened by remember(message.id) { mutableStateOf(false) }
    TextButton(onClick = { opened = true }) { Text("请求体积", style = MaterialTheme.typography.labelSmall) }
    if (!opened) return
    fun size(bytes: Long): String = String.format(Locale.ROOT, "%.2f KiB", bytes / 1024.0)
    AlertDialog(
        onDismissRequest = { opened = false },
        title = { Text("本次模型请求构成") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("这是该回复最后一次模型调用前的 UTF-8 文本大小，不是计费 token。图片、音频及协议包装未计入；不同接口可能进一步过滤消息。")
                Text("消息 ${profile.messageCount} 条 · 文本 ${size(profile.textBytes)}")
                Text("工具 ${profile.toolCount} 个 · 定义约 ${size(profile.toolSchemaBytes)}（按 OpenAI 格式计算）")
                profile.discovery?.let { discovery ->
                    Text("工具按需加载（本轮）", style = MaterialTheme.typography.titleSmall)
                    Text("已启用 ${discovery.availableCount} · 常驻 ${discovery.residentCount} · 临时加载 ${discovery.loadedCount}")
                    Text("全量定义约 ${size(discovery.fullSchemaBytes)} · 简短目录 ${size(discovery.directoryBytes)}")
                    Text("上方工具定义是本次实际发送的部分，已包含查找入口；目录计入消息文本。这里不是 token 估算。")
                }
                Text("最终消息按角色统计", style = MaterialTheme.typography.titleSmall)
                profile.roles.forEach { Text("${it.label}：${size(it.bytes)}") }
                Text("初始系统提示构成（已包含在消息文本中）", style = MaterialTheme.typography.titleSmall)
                profile.systemParts.filter { it.bytes > 0 }.forEach { Text("${it.label}：${size(it.bytes)}") }
                Text("发送前转换的文本增减", style = MaterialTheme.typography.titleSmall)
                val names = mapOf(
                    "PromptInjectionTransformer" to "世界书/模式注入",
                    "TemplateTransformer" to "消息模板",
                    "TimeReminderTransformer" to "时间提醒",
                    "DocumentAsPromptTransformer" to "文档转文本",
                    "OcrTransformer" to "图片识别",
                    "VoiceMessageTransformer" to "语音处理",
                    "PlaceholderTransformer" to "占位符替换",
                    "WorkspaceReminderTransformer" to "工作区说明",
                )
                profile.transformations.filter { it.bytes != 0L }.forEach {
                    Text("${names[it.label] ?: it.label}：${if (it.bytes > 0) "+" else ""}${size(it.bytes)}")
                }
                Text("按需记忆召回：+${size(profile.recallBytes)}")
                Text("体积最大的工具定义", style = MaterialTheme.typography.titleSmall)
                profile.largestTools.forEach { Text("${it.label}：${size(it.bytes)}") }
                if (profile.customBodyKeys > 0) Text("配置了 ${profile.customBodyKeys} 项自定义请求体参数，可能覆盖消息或工具；以上是覆盖前统计。")
                Text("分类是定位体积来源的不同视角，请勿全部相加。这里只保存大小和名称，不保存提示词正文、密钥或完整请求。")
            }
        },
        confirmButton = { TextButton(onClick = { opened = false }) { Text("关闭") } },
    )
}
