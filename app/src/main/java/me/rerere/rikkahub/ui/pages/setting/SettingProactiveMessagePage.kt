/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.ui.platform.LocalConfiguration
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
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
import me.rerere.rikkahub.data.service.isProactiveQuietTime
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import org.koin.compose.koinInject
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingProactiveMessagePage(vm: SettingVM = koinInject()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scheduleStore: me.rerere.rikkahub.data.datastore.SettingsStore = koinInject()
    val scope = rememberCoroutineScope()

    var showProactiveRiskDialog by remember { mutableStateOf(false) }
    var editingQuietStart by remember { mutableStateOf<Boolean?>(null) }
    val testStatus by ProactiveMessageService.manualTestStatus.collectAsStateWithLifecycle()

    fun updateSchedule(change: (ProactiveMessageSetting) -> ProactiveMessageSetting) {
        scope.launch {
            try {
                scheduleStore.update { current ->
                    val changed = change(current.proactiveMessageSetting)
                    val updated =
                        if (changed.enabled && changed.allowAiWakeups) changed
                        else
                            changed.copy(
                                aiWakeups =
                                    changed.aiWakeups.map { wakeup ->
                                        if (
                                            wakeup.status in
                                                setOf(
                                                    me.rerere.rikkahub.data.datastore
                                                        .ProactiveWakeupStatus
                                                        .Pending,
                                                    me.rerere.rikkahub.data.datastore
                                                        .ProactiveWakeupStatus
                                                        .Running,
                                                )
                                        )
                                            wakeup.copy(
                                                status =
                                                    me.rerere.rikkahub.data.datastore
                                                        .ProactiveWakeupStatus
                                                        .Cancelled,
                                                outcome = "用户已关闭主动唤醒",
                                            )
                                        else wakeup
                                    }
                            )
                    current.copy(proactiveMessageSetting = updated)
                }
                ProactiveMessageService.scheduleNext(
                    context,
                    scheduleStore.settingsFlowRaw.first().proactiveMessageSetting,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.widget.Toast.makeText(
                        context,
                        "保存主动消息设置失败，请重试",
                        android.widget.Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        }
    }

    editingQuietStart?.let { start ->
        val current = settings.proactiveMessageSetting
        val minute = (if (start) current.quietStartMinute else current.quietEndMinute).coerceIn(0, 1439)
        val picker = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { editingQuietStart = null },
            title = { Text(if (start) "免打扰开始时间" else "免打扰结束时间") },
            text = { TimeInput(state = picker) },
            confirmButton = {
                TextButton(onClick = {
                    val selected = picker.hour * 60 + picker.minute
                    updateSchedule {
                        if (start) it.copy(quietStartMinute = selected) else it.copy(quietEndMinute = selected)
                    }
                    editingQuietStart = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editingQuietStart = null }) { Text("取消") } },
        )
    }

    if (showProactiveRiskDialog) {
        RiskConfirmDialog(
            title = stringResource(R.string.risk_proactive_message_title),
            message = stringResource(R.string.risk_proactive_message_message),
            onConfirm = {
                showProactiveRiskDialog = false
                updateSchedule { it.copy(enabled = true) }
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
                        supportingContent = { Text("开启后按设定间隔检查，AI 自行决定发送、等待或停止；免打扰时段暂停自动检查。") },
                        trailingContent = {
                            Switch(
                                checked = settings.proactiveMessageSetting.enabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        showProactiveRiskDialog = true
                                    } else {
                                        updateSchedule { it.copy(enabled = false) }
                                    }
                                }
                            )
                        }
                    )
                    if (settings.proactiveMessageSetting.enabled) {
                        var nextTime by remember { mutableStateOf(ProactiveMessageService.getNextTriggerTime(context)) }
                        var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(settings.proactiveMessageSetting) {
                            nextTime = ProactiveMessageService.getNextTriggerTime(context)
                            while (true) {
                                kotlinx.coroutines.delay(10_000L)
                                currentTime = System.currentTimeMillis()
                                nextTime = ProactiveMessageService.getNextTriggerTime(context)
                            }
                        }
                        item(
                            headlineContent = { Text("下次触发时间") },
                            supportingContent = {
                                Column {
                                    val triggerTime = nextTime
                                    if (isProactiveQuietTime(
                                            settings.proactiveMessageSetting, currentTime, java.time.ZoneId.systemDefault(),
                                        )
                                    ) {
                                        Text("当前处于免打扰时段，结束后自动恢复检查。")
                                    }
                                    if (triggerTime != null && triggerTime > currentTime) {
                                        val remaining = triggerTime - currentTime
                                        val remainMinutes = remaining / 60_000
                                        val remainSeconds = (remaining % 60_000) / 1000
                                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", LocalConfiguration.current.locales[0])
                                        Text("🕐 ${sdf.format(java.util.Date(triggerTime))}（剩余 ${remainMinutes}分${remainSeconds}秒）")
                                    } else {
                                        Text("等待调度中...")
                                    }
                                }
                            }
                        )
                    }
                    item(
                        headlineContent = { Text("立即测试一次") },
                        supportingContent = {
                            Column {
                                Text("忽略免打扰、最小间隔和连续追问限制，只运行一次判断；AI 仍可选择暂不发送，不改变自动调度规则。")
                                Text(proactiveTestStatusText(testStatus))
                            }
                        },
                        trailingContent = {
                            Button(
                                enabled = settings.proactiveMessageSetting.enabled && !settings.init && testStatus != "running",
                                onClick = { ProactiveMessageService.triggerNow(context, vm.settings.value.proactiveMessageSetting) },
                            ) { Text(if (testStatus == "running") "测试中" else "测试") }
                        },
                    )
                }
            }
            item {
                ProactiveWakeupSettingsCard(settings.proactiveMessageSetting, settings.assistants,
                    initialized = !settings.init, onChange = ::updateSchedule)
            }
            item {
                CardGroup {
                    item(
                        headlineContent = { Text("定时免打扰") },
                        supportingContent = { Text("每天按手机本地时间暂停自动主动消息，时段结束后恢复；不会自动打开已被你关闭的总开关。") },
                        trailingContent = {
                            Switch(
                                checked = settings.proactiveMessageSetting.quietHoursEnabled,
                                enabled = !settings.init,
                                onCheckedChange = { enabled -> updateSchedule { it.copy(quietHoursEnabled = enabled) } },
                            )
                        },
                    )
                    if (settings.proactiveMessageSetting.quietHoursEnabled) {
                        item(
                            headlineContent = { Text("开始时间") },
                            supportingContent = { Text(formatQuietMinute(settings.proactiveMessageSetting.quietStartMinute)) },
                            onClick = { editingQuietStart = true },
                        )
                        item(
                            headlineContent = { Text("结束时间") },
                            supportingContent = { Text(formatQuietMinute(settings.proactiveMessageSetting.quietEndMinute)) },
                            onClick = { editingQuietStart = false },
                        )
                        item(
                            headlineContent = { Text("支持跨午夜，例如 23:00–08:00；起止相同时不限制时段。") },
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
                                        updateSchedule { it.copy(minIntervalMinutes = minutes) }
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
                                        updateSchedule { it.copy(maxIntervalMinutes = minutes) }
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
                    item(
                        headlineContent = { Text("额外注入") },
                        supportingContent = {
                            Text("主动消息会收集额外注入中已开启的内容，再由 AI 决定如何回复。屏幕文字识别也使用额外注入设置；应用使用信息仍需开启主动消息查岗。")
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

private fun formatQuietMinute(value: Int): String = value.coerceIn(0, 1439).let {
    "%02d:%02d".format(it / 60, it % 60)
}

private fun proactiveTestStatusText(status: String): String = when (status) {
    "idle" -> "启用主动消息后，可在这里手动测试。"
    "running" -> "正在请求模型，请稍候…"
    "sent" -> "本次测试已发送消息。"
    "pass" -> "判断已完成：AI 这次选择不发送。"
    "wait" -> "判断已完成：AI 这次选择继续等待。"
    "stop" -> "判断已完成：AI 这次选择暂不打扰。"
    "disabled" -> "主动消息总开关已关闭。"
    "generation_busy", "duplicate_trigger" -> "当前已有生成任务，请稍后再试。"
    "no_user_message" -> "请先和选定的助手聊一句，再测试。"
    "no_model", "no_provider" -> "请先配置好助手使用的模型和接口。"
    "blocked_chat" -> "当前会话已被暂停，暂时不能测试。"
    "cancelled", "user_returned" -> "测试已中止：对话状态发生了变化。"
    else -> "本次测试未完成，请到日志页面查看原因。"
}
