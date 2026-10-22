package me.rerere.rikkahub.ui.pages.backup.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.backup.AUTO_BACKUP_INTERVALS
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun AutoBackupSettings(vm: BackupVM) {
    val state by vm.autoBackup.collectAsStateWithLifecycle()
    val running by vm.autoBackupRunning.collectAsStateWithLifecycle()
    val config = state.config
    var pickingTime by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.chooseAutoBackupFolder(uri)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("自动备份", style = MaterialTheme.typography.titleMedium)
        CardGroup {
            item(
                headlineContent = { Text("定期保存本地备份") },
                supportingContent = { Text("与本地导出格式相同，包含聊天、设置、附件及插件，只保留一份最新备份。") },
                trailingContent = {
                    Switch(
                        checked = config.enabled,
                        enabled = !running && config.folder.isNotBlank(),
                        onCheckedChange = { enabled -> vm.configureAutoBackup { it.copy(enabled = enabled) } },
                    )
                },
            )
            item(
                onClick = if (running) null else {
                    { picker.launch(config.folder.takeIf { it.isNotBlank() }?.let(Uri::parse)) }
                },
                headlineContent = { Text("保存文件夹") },
                supportingContent = {
                    Text(if (config.folder.isBlank()) "点击选择文件夹并允许读写" else
                        Uri.decode(config.folder.substringAfterLast('/')).substringAfter(':').ifBlank { "已选择文件夹" })
                },
            )
            item(
                onClick = if (running) null else { { pickingTime = true } },
                headlineContent = { Text("计划备份时间") },
                supportingContent = {
                    Text("%02d:%02d（手机本地时间）".format(config.hour, config.minute))
                },
            )
        }
        Text("备份间隔", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AUTO_BACKUP_INTERVALS.forEach { days ->
                FilterChip(
                    selected = config.intervalDays == days,
                    enabled = !running,
                    onClick = { vm.configureAutoBackup { it.copy(intervalDays = days) } },
                    label = { Text(if (days == 1) "每天" else "每 $days 天") },
                )
            }
        }
        Text(
            "默认凌晨 3 点，系统省电限制可能延后。新备份写入并校验成功后才清理旧备份，失败时保留旧备份。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.lastSuccess > 0) Text(
            "上次成功：" + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(state.lastSuccess)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.error.isNotBlank()) Text(
            state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        )
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(
            onClick = vm::runAutoBackupNow,
            enabled = !running && config.folder.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "正在备份并校验…" else "立即备份") }
    }
    if (pickingTime) {
        val time = rememberTimePickerState(initialHour = config.hour, initialMinute = config.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = { Text("备份时间") },
            text = { TimeInput(state = time, modifier = Modifier.padding(top = 8.dp)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.configureAutoBackup { it.copy(hour = time.hour, minute = time.minute) }
                    pickingTime = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("取消") } },
        )
    }
}
