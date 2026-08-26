/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RiskConfirmDialog
import me.rerere.rikkahub.data.service.ProactiveMessageService
import me.rerere.rikkahub.data.service.ProactiveMessageWorker
import org.koin.compose.koinInject
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingProactiveMessagePage(vm: SettingVM = koinInject()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var showProactiveRiskDialog by remember { mutableStateOf(false) }

    if (showProactiveRiskDialog) {
        RiskConfirmDialog(
            title = stringResource(R.string.risk_proactive_message_title),
            message = stringResource(R.string.risk_proactive_message_message),
            onConfirm = {
                showProactiveRiskDialog = false
                val newSetting = settings.proactiveMessageSetting.copy(enabled = true, aggressiveModeEnabled = false)
                vm.updateSettings(settings.copy(proactiveMessageSetting = newSetting))
                me.rerere.rikkahub.data.service.DeviceEventAiTriggerService.stop(context)
                ProactiveMessageService.triggerNow(context, newSetting)
            },
            onDismiss = { showProactiveRiskDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("主动消息") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                CardGroup {
                    item(
                        headlineContent = { Text("启用主动消息") },
                        supportingContent = { Text("开启后AI立即主动发一条消息，之后按设定间隔循环") },
                        trailingContent = {
                            Switch(
                                checked = settings.proactiveMessageSetting.enabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        showProactiveRiskDialog = true
                                    } else {
                                        val newSetting = settings.proactiveMessageSetting.copy(enabled = false)
                                        vm.updateSettings(settings.copy(proactiveMessageSetting = newSetting))
                                        ProactiveMessageService.cancel(context)
                                    }
                                }
                            )
                        }
                    )
                    if (settings.proactiveMessageSetting.enabled) {
                        var nextTime by remember { mutableStateOf(ProactiveMessageService.getNextTriggerTime(context)) }
                        LaunchedEffect(settings.proactiveMessageSetting) {
                            nextTime = ProactiveMessageService.getNextTriggerTime(context)
                            while (true) {
                                kotlinx.coroutines.delay(10_000L)
                                nextTime = ProactiveMessageService.getNextTriggerTime(context)
                            }
                        }
                        item(
                            headlineContent = { Text("下次触发时间") },
                            supportingContent = {
                                val currentTime = System.currentTimeMillis()
                                val triggerTime = nextTime
                                if (triggerTime != null && triggerTime > currentTime) {
                                    val remaining = triggerTime - currentTime
                                    val remainMinutes = remaining / 60_000
                                    val remainSeconds = (remaining % 60_000) / 1000
                                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                    Text("🕐 ${sdf.format(java.util.Date(triggerTime))}（剩余 ${remainMinutes}分${remainSeconds}秒）")
                                } else {
                                    Text("等待调度中...")
                                }
                            }
                        )
                    }
                }
            }
            item {
                CardGroup {
                    item(
                        headlineContent = { Text("最小间隔 (分钟)") },
                        supportingContent = {
                            OutlinedTextField(
                                value = settings.proactiveMessageSetting.minIntervalMinutes.toString(),
                                onValueChange = { value ->
                                    val minutes = value.toIntOrNull()
                                    if (minutes != null && minutes > 0) {
                                        vm.updateSettings(
                                            settings.copy(
                                                proactiveMessageSetting = settings.proactiveMessageSetting.copy(
                                                    minIntervalMinutes = minutes
                                                )
                                            )
                                        )
                                    }
                                },
                                placeholder = { Text("30") },
                                singleLine = true,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("最大间隔 (分钟)") },
                        supportingContent = {
                            OutlinedTextField(
                                value = settings.proactiveMessageSetting.maxIntervalMinutes.toString(),
                                onValueChange = { value ->
                                    val minutes = value.toIntOrNull()
                                    if (minutes != null && minutes >= settings.proactiveMessageSetting.minIntervalMinutes) {
                                        vm.updateSettings(
                                            settings.copy(
                                                proactiveMessageSetting = settings.proactiveMessageSetting.copy(
                                                    maxIntervalMinutes = minutes
                                                )
                                            )
                                        )
                                    }
                                },
                                placeholder = { Text("90") },
                                singleLine = true,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("最多连续追问") },
                        supportingContent = {
                            OutlinedTextField(
                                value = settings.proactiveMessageSetting.maxFollowUpMessages.toString(),
                                onValueChange = { value ->
                                    value.toIntOrNull()?.takeIf { it in 1..8 }?.let { count ->
                                        vm.updateSettings(
                                            settings.copy(
                                                proactiveMessageSetting = settings.proactiveMessageSetting.copy(
                                                    maxFollowUpMessages = count,
                                                ),
                                            ),
                                        )
                                    }
                                },
                                placeholder = { Text("2") },
                                singleLine = true,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text("同一次沉默后最多发送几次，可设置 1～8 次；重新开口后自动清零。")
                        },
                    )
                    item(
                        headlineContent = { Text("允许主动消息查岗") },
                        supportingContent = {
                            Text(
                                "开启后，AI 可在主动消息判断确有需要时调用应用使用工具；" +
                                    "关闭后，主动消息不会读取应用使用情况。此设置不影响正常聊天中的工具使用。",
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.proactiveMessageSetting.allowProactiveAppUsage,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            proactiveMessageSetting = settings.proactiveMessageSetting.copy(
                                                allowProactiveAppUsage = enabled,
                                            ),
                                        ),
                                    )
                                },
                            )
                        },
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    val hasExactAlarm = ProactiveMessageWorker.canScheduleExactAlarms(context)
                    CardGroup {
                        item(
                            headlineContent = { Text("精确闹钟权限") },
                            supportingContent = {
                                if (hasExactAlarm) {
                                    Text("已授予精确闹钟权限，定时触发将更准确")
                                } else {
                                    Text("未授予精确闹钟权限，触发时间可能不精确。已自动使用 WorkManager 作为备用方案。")
                                }
                            },
                            onClick = if (!hasExactAlarm) {
                                {
                                    try {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                                        context.startActivity(intent)
                                    }
                                }
                            } else null
                        )
                    }
                }
            }
            item {
                val isIgnoring = ProactiveMessageWorker.isIgnoringBatteryOptimizations(context)
                CardGroup {
                    item(
                        headlineContent = { Text("电池优化") },
                        supportingContent = {
                            if (isIgnoring) {
                                Text("已忽略电池优化，后台触发更稳定")
                            } else {
                                Text("未忽略电池优化，系统可能限制后台活动导致消息无法准时触发。建议关闭电池优化。")
                            }
                        },
                        onClick = if (!isIgnoring) {
                            {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                }
                            }
                        } else null
                    )
                }
            }
            item {
                CardGroup {
                    item(
                        headlineContent = { Text("说明") },
                        supportingContent = {
                            Text("AI 会先判断聊天中的人是突然离开、明确去忙，还是对话已经结束，再决定发送、等待或停止。上一轮主动消息会作为真实交流保留，达到连续追问上限后会等待再次开口。\n\n只有同时开启系统里的应用使用工具和本页的查岗权限，主动消息才可以按需查看；未调用就不会附带这份数据。AI 也可以自行用 JUMP 拉起聊天页。\n\nAlarmManager 与 WorkManager 会共同保证后台调度。")
                        },
                    )
                }
            }
        }
    }
}
