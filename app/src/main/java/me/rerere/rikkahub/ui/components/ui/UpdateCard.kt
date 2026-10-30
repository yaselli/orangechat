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
import me.rerere.rikkahub.ui.theme.MaterialAlertDialog as AlertDialog
import me.rerere.rikkahub.ui.theme.MaterialCard as Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.hooks.rememberIsPlayStoreVersion
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceString
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.utils.AppUpdateDownloader
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateDownloadPhase
import me.rerere.rikkahub.utils.UpdateDownloadState
import me.rerere.rikkahub.utils.UpdateInfo
import me.rerere.rikkahub.utils.openUrl
import org.koin.compose.koinInject

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
        if (
            enabled &&
                ignoredCode != info.versionCode.toString() &&
                vm.updateChecker.claimAutomaticPrompt(info.versionCode)
        )
            visible = true
    }
    if (visible && enabled) {
        AppUpdateDialog(
            info = info,
            onDismiss = { visible = false },
            onSkip = {
                ignoredCode = info.versionCode.toString()
                visible = false
            },
        )
    }
}

@Composable
fun UpdateCard(vm: ChatVM) {
    val state by vm.updateState.collectAsStateWithLifecycle()
    val info = (state as? UiState.Success)?.data ?: return
    var visible by remember(info.versionCode) { mutableStateOf(false) }
    Card(onClick = { visible = true }) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("发现新版本 ${info.version}", style = MaterialTheme.typography.titleSmall)
            Text("点击查看更新内容", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (visible) {
        AppUpdateDialog(
            info = info,
            onDismiss = { visible = false },
        )
    }
}

@Composable
fun AppUpdateDialog(
    info: UpdateInfo,
    onDismiss: () -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val downloads = koinInject<AppUpdateDownloader>()
    val globalState by downloads.state.collectAsStateWithLifecycle()
    val state =
        globalState.takeIf { it.info?.download == info.download }
            ?: UpdateDownloadState(error = globalState.error.takeIf { globalState.info == null })
    val otherDownload =
        globalState.info?.download != info.download &&
            globalState.phase in
                listOf(
                    UpdateDownloadPhase.Waiting,
                    UpdateDownloadPhase.Downloading,
                    UpdateDownloadPhase.Verifying,
                )
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val busy =
        state.phase in
            listOf(
                UpdateDownloadPhase.Waiting,
                UpdateDownloadPhase.Downloading,
                UpdateDownloadPhase.Verifying,
            )
    DisposableEffect(lifecycle, downloads, info.download) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                downloads.resumeInstall(context)
                val current = downloads.state.value
                if (
                    current.info?.download == info.download &&
                        current.phase == UpdateDownloadPhase.Ready &&
                        current.autoInstall
                ) {
                    downloads.install(context)
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.phase, state.autoInstall) {
        if (
            state.phase == UpdateDownloadPhase.Ready &&
                state.autoInstall &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            downloads.install(context)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${info.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MarkdownBlock(
                    content = info.changelog,
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("适配当前设备 · ${info.download.size}", style = MaterialTheme.typography.bodySmall)
                if (state.phase != UpdateDownloadPhase.Idle) {
                    Text(updateDownloadLabel(state), style = MaterialTheme.typography.bodyMedium)
                }
                if (state.phase == UpdateDownloadPhase.Downloading) {
                    LinearProgressIndicator(
                        progress = {
                            if (state.total > 0)
                                (state.received.toFloat() / state.total).coerceIn(0f, 1f)
                            else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${updateBytes(state.received)} / ${updateBytes(state.total)} · ${updateBytes(state.bytesPerSecond)}/s",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (
                    state.phase in
                        listOf(UpdateDownloadPhase.Waiting, UpdateDownloadPhase.Verifying)
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (otherDownload)
                    Text(
                        "另一个更新正在下载，可从「设置 → 关于 → 更新下载」查看。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                Text(
                    if (state.waitingForPermission) "请允许西瓜瓣安装应用，返回后继续安装。"
                    else "下载记录保留在「设置 → 关于 → 更新下载」，完成后打开系统安装页面。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (busy) TextButton(onClick = onDismiss) { Text("收起，继续下载") }
                else TextButton(onClick = { context.openUrl(info.download.url) }) { Text("用浏览器下载") }
                if (onSkip != null && !busy && state.phase != UpdateDownloadPhase.Ready) {
                    TextButton(onClick = onSkip) { Text("忽略此版本") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !otherDownload,
                onClick = {
                    if (state.phase == UpdateDownloadPhase.Ready) downloads.install(context)
                    else downloads.start(info)
                },
            ) {
                Text(
                    when (state.phase) {
                        UpdateDownloadPhase.Ready ->
                            if (state.waitingForPermission) "允许安装" else "安装更新"
                        UpdateDownloadPhase.Paused,
                        UpdateDownloadPhase.Failed -> "继续下载"
                        UpdateDownloadPhase.Waiting -> "等待网络…"
                        UpdateDownloadPhase.Downloading -> "正在下载…"
                        UpdateDownloadPhase.Verifying -> "正在检查…"
                        else -> "下载并安装"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { if (busy) downloads.cancel() else onDismiss() }) {
                Text(if (busy) "暂停下载" else "稍后")
            }
        },
    )
}

fun updateDownloadLabel(state: UpdateDownloadState): String =
    when (state.phase) {
        UpdateDownloadPhase.Downloading ->
            "下载中 ${if (state.total > 0) state.received * 100 / state.total else 0}%"
        UpdateDownloadPhase.Waiting -> "等待网络或系统开始下载，已有进度会保留"
        UpdateDownloadPhase.Verifying -> "正在检查安装包…"
        UpdateDownloadPhase.Ready -> "下载完成，可以安装"
        UpdateDownloadPhase.Paused -> "下载已暂停，可以继续"
        UpdateDownloadPhase.Failed -> "下载未完成，可以重试"
        UpdateDownloadPhase.Idle -> "准备下载"
    }

private fun updateBytes(bytes: Long): String =
    if (bytes < 1024 * 1024) "${bytes.coerceAtLeast(0) / 1024} KB"
    else String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
