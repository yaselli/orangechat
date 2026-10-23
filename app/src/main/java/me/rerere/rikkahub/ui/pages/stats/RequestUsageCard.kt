package me.rerere.rikkahub.ui.pages.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.CustomColors
import java.time.format.DateTimeFormatter

@Composable
internal fun RequestUsageCard(
    stats: RequestUsageStats,
    onRangeChange: (UsageRange) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("API 用量", style = MaterialTheme.typography.titleMedium)
            Text(
                "从本次升级后开始记录，仅包含接口返回的文本模型用量；历史消息不回填。" +
                    "缓存 token 属于输入的一部分，不额外加到总量中。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UsageRange.entries.forEach { range ->
                    FilterChip(
                        selected = stats.range == range,
                        onClick = { onRangeChange(range) },
                        label = { Text(range.days?.let { "近 $it 天" } ?: "全部") },
                    )
                }
            }
            when {
                stats.isLoading -> CircularProgressIndicator()
                stats.hasError -> {
                    Text("用量读取失败，请重试。")
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                stats.totals.requestCount == 0L -> Text("这段时间还没有接口返回的用量记录。")
                else -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        UsageValue("请求次数", stats.totals.requestCount.toString())
                        UsageValue("总 token", formatTokens(stats.totals.promptTokens + stats.totals.completionTokens))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        UsageValue("输入", formatTokens(stats.totals.promptTokens))
                        UsageValue("输出", formatTokens(stats.totals.completionTokens))
                        UsageValue("缓存", formatTokens(stats.totals.cachedTokens))
                    }
                    if (stats.totals.promptTokens > 0) {
                        val rate = stats.totals.cachedTokens.toDouble() / stats.totals.promptTokens * 100
                        Text("缓存命中占输入：${"%.1f".format(rate)}%", style = MaterialTheme.typography.bodySmall)
                    }
                    UsageTrend(stats.points, stats.range == UsageRange.ALL)
                    UsageRanks("按模型", stats.models)
                    UsageRanks("按用途", stats.sources, sourceNames = true)
                }
            }
        }
    }
}

@Composable
private fun UsageValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UsageTrend(points: List<UsagePoint>, monthly: Boolean) {
    if (points.isEmpty()) return
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    val inputColor = MaterialTheme.colorScheme.primary
    val outputColor = MaterialTheme.colorScheme.tertiary
    val dateFormat = remember(monthly) { DateTimeFormatter.ofPattern(if (monthly) "yyyy-MM" else "MM-dd") }
    Text(if (monthly) "每月用量 · 点按查看" else "每日用量 · 点按查看",
        style = MaterialTheme.typography.titleSmall)
    Canvas(
        Modifier.fillMaxWidth().height(130.dp)
            .semantics { contentDescription = if (monthly) "按月输入与输出用量趋势" else "按日输入与输出用量趋势" }
            .pointerInput(points) {
                detectTapGestures { offset ->
                    selected = (offset.x / size.width * points.size).toInt().coerceIn(points.indices)
                }
            },
    ) {
        val max = (points.maxOfOrNull { it.total } ?: 0).coerceAtLeast(1).toFloat()
        val step = size.width / points.size
        val width = (step * 0.75f).coerceAtLeast(1f)
        points.forEachIndexed { index, point ->
            val inputHeight = point.input / max * size.height
            val outputHeight = point.output / max * size.height
            val left = index * step + (step - width) / 2
            val alpha = if (index == selected) 1f else 0.65f
            drawRect(inputColor.copy(alpha = alpha),
                Offset(left, size.height - inputHeight), Size(width, inputHeight))
            drawRect(outputColor.copy(alpha = alpha),
                Offset(left, size.height - inputHeight - outputHeight), Size(width, outputHeight))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(points.first().date.format(dateFormat), style = MaterialTheme.typography.labelSmall)
        Text(points.last().date.format(dateFormat), style = MaterialTheme.typography.labelSmall)
    }
    val point = points[selected]
    Text("${point.date.format(dateFormat)} · 总计 ${formatTokens(point.total)}",
        style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("输入 ${formatTokens(point.input)}", color = inputColor, style = MaterialTheme.typography.bodySmall)
        Text("输出 ${formatTokens(point.output)}", color = outputColor, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun UsageRanks(title: String, ranks: List<UsageRank>, sourceNames: Boolean = false) {
    if (ranks.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.titleSmall)
    (if (expanded) ranks else ranks.take(5)).forEach { row ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (sourceNames) usageSourceName(row.name) else row.name,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(formatTokens(row.total), style = MaterialTheme.typography.labelLarge)
            }
            Text(
                "输入 ${formatTokens(row.input)} · 输出 ${formatTokens(row.output)} · 缓存 ${formatTokens(row.cached)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (ranks.size > 5) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起" else "展开全部（${ranks.size}）")
        }
    }
}

private fun usageSourceName(source: String): String = when (source) {
    "CHAT" -> "聊天"
    "COMPRESSION" -> "上下文压缩"
    "TITLE" -> "标题生成"
    "SUGGESTION" -> "建议回复"
    "TRANSLATION" -> "翻译"
    "MEMORY" -> "记忆整理"
    "OCR" -> "图片文字识别"
    "PROACTIVE" -> "主动消息／后台续聊"
    "VOICE_EVENT" -> "通话事件回复"
    "PLUGIN" -> "插件"
    "CONNECTION_TEST" -> "模型连接测试"
    else -> source
}
