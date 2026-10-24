/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.hooks.rememberIsPlayStoreVersion
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceString
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateInfo

/** Automatic notice lives on the chat page, independent of whether the drawer is open. */
@Composable
fun AppUpdatePrompt(vm: ChatVM) {
    val state by vm.updateState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val isPlayStore = rememberIsPlayStoreVersion()
    val info = (state as? UiState.Success)?.data ?: return
    var ignoredCode by rememberSharedPreferenceString("ignored_update_version_code")
    var visible by rememberSaveable(info.versionCode) { mutableStateOf(false) }
    val enabled = !settings.init && settings.displaySetting.showUpdates && !isPlayStore
    LaunchedEffect(info.versionCode, enabled, ignoredCode) {
        if (enabled && ignoredCode != info.versionCode.toString() &&
            vm.updateChecker.claimAutomaticPrompt(info.versionCode)
        ) visible = true
    }
    val context = LocalContext.current
    if (visible && enabled) {
        AppUpdateDialog(
            info = info,
            onDismiss = { visible = false },
            onSkip = { ignoredCode = info.versionCode.toString(); visible = false },
            onDownload = { vm.updateChecker.downloadUpdate(context, info.download); visible = false },
        )
    }
}

@Composable
fun UpdateCard(vm: ChatVM) {
    val state by vm.updateState.collectAsStateWithLifecycle()
    val info = (state as? UiState.Success)?.data ?: return
    val context = LocalContext.current
    var visible by remember(info.versionCode) { mutableStateOf(false) }
    Card(onClick = { visible = true }) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("发现新版本 ${info.version}", style = MaterialTheme.typography.titleSmall)
            Text("点击查看更新内容", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (visible) {
        AppUpdateDialog(
            info = info,
            onDismiss = { visible = false },
            onDownload = { vm.updateChecker.downloadUpdate(context, info.download); visible = false },
        )
    }
}

@Composable
fun AppUpdateDialog(
    info: UpdateInfo,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${info.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MarkdownBlock(
                    content = info.changelog,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("适配当前设备 · ${info.download.size}", style = MaterialTheme.typography.bodySmall)
                Text("下载完成后，请从通知栏打开安装包。", style = MaterialTheme.typography.bodySmall)
                if (onSkip != null) TextButton(onClick = onSkip) { Text("忽略此版本") }
            }
        },
        confirmButton = { TextButton(onClick = onDownload) { Text("下载更新") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
    )
}
