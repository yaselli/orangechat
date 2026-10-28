package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.ProactiveWakeupStatus
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.service.nextProactiveAllowedTime
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Composable
internal fun ProactiveWakeupSettingsCard(
    setting: ProactiveMessageSetting,
    assistants: List<Assistant>,
    initialized: Boolean,
    onChange: ((ProactiveMessageSetting) -> ProactiveMessageSetting) -> Unit,
) {
    CardGroup {
        item(
            headlineContent = { Text("允许 AI 约定主动唤醒") },
            supportingContent = {
                Text(
                    "开启后，各角色可为当前会话约定下次几点几分来找你；到点读取最新聊天生成回复。每个会话保留一个待执行约定，遵守总开关和免打扰；有约定时等待约定时间，无约定时按原间隔检查。"
                )
            },
            trailingContent = {
                Switch(
                    checked = setting.allowAiWakeups,
                    enabled = initialized && setting.enabled,
                    onCheckedChange = { enabled -> onChange { it.copy(allowAiWakeups = enabled) } },
                )
            },
        )
        if (setting.allowAiWakeups) {
            item(
                headlineContent = {
                    Text("可在聊天中说“今晚九点半来找我”，AI 也可以自行约定。沿用本页的后台运行与精确闹钟设置，实际触发可能受系统限制而延迟。")
                }
            )
        }
        if (setting.aiWakeups.isEmpty() && setting.allowAiWakeups) {
            item(headlineContent = { Text("还没有主动唤醒约定") })
        }
        setting.aiWakeups
            .sortedWith(
                compareBy(
                    {
                        if (
                            it.status in
                                setOf(ProactiveWakeupStatus.Pending, ProactiveWakeupStatus.Running)
                        )
                            0
                        else 1
                    },
                    { it.atMillis },
                )
            )
            .forEach { wakeup ->
                val role =
                    assistants
                        .firstOrNull { it.id.toString() == wakeup.assistantId }
                        ?.name
                        ?.ifBlank { "AI" } ?: "已删除角色"
                val zone = ZoneId.systemDefault()
                val time =
                    Instant.ofEpochMilli(wakeup.atMillis)
                        .atZone(zone)
                        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                val active =
                    wakeup.status in
                        setOf(ProactiveWakeupStatus.Pending, ProactiveWakeupStatus.Running)
                val status =
                    when (wakeup.status) {
                        ProactiveWakeupStatus.Pending -> {
                            val effective =
                                nextProactiveAllowedTime(
                                    setting,
                                    maxOf(wakeup.atMillis, wakeup.notBeforeMillis),
                                    zone,
                                )
                            if (effective > wakeup.atMillis)
                                "等待执行 · 顺延至 " +
                                    Instant.ofEpochMilli(effective)
                                        .atZone(zone)
                                        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                            else "等待执行"
                        }
                        ProactiveWakeupStatus.Running -> "正在唤醒"
                        ProactiveWakeupStatus.Sent -> "已发送"
                        ProactiveWakeupStatus.Failed -> wakeup.outcome.ifBlank { "未完成，可让 AI 重新安排" }
                        ProactiveWakeupStatus.Cancelled -> wakeup.outcome.ifBlank { "已取消" }
                    }
                item(
                    headlineContent = { Text("$role · $time · $status") },
                    supportingContent = {
                        Text(wakeup.reason, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent =
                        if (active)
                            ({
                                TextButton(
                                    onClick = {
                                        onChange { current ->
                                            current.copy(
                                                aiWakeups =
                                                    current.aiWakeups.map {
                                                        if (it.id == wakeup.id)
                                                            it.copy(
                                                                status =
                                                                    ProactiveWakeupStatus.Cancelled,
                                                                outcome = "用户已取消",
                                                            )
                                                        else it
                                                    }
                                            )
                                        }
                                    }
                                ) {
                                    Text("取消")
                                }
                            })
                        else null,
                )
            }
    }
}
