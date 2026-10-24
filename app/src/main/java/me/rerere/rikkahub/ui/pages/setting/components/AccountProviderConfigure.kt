package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.account.AccountLoginStatus
import me.rerere.rikkahub.data.ai.account.ProviderAccountManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.compose.koinInject
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun AccountProviderConfigure(provider: ProviderSetting.Account, onEdit: (ProviderSetting) -> Unit) {
    val store: SettingsStore = koinInject()
    val manager: ProviderAccountManager = koinInject()
    val providers: ProviderManager = koinInject()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val statusMap by manager.loginStatus.collectAsStateWithLifecycle()
    val stored = settings.providers.find { it.id == provider.id && it::class == provider::class } as? ProviderSetting.Account
    val credential = stored?.credential
    val latestCredential by rememberUpdatedState(credential)
    val status = statusMap[provider.id]
    val authorizing = status is AccountLoginStatus.Authorizing || status is AccountLoginStatus.DeviceCode
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var quotaText by remember(provider.id) { mutableStateOf<String?>(null) }
    var quotaLoading by remember(provider.id) { mutableStateOf(false) }
    LaunchedEffect(credential?.accountId, credential?.email, credential == null) { quotaText = null }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (provider is ProviderSetting.ChatGPT) "ChatGPT 订阅账号" else "Grok 订阅账号",
            style = MaterialTheme.typography.titleMedium)
        Text(if (provider is ProviderSetting.ChatGPT) "通过 Codex 浏览器授权登录，使用账号可用的模型与订阅额度。"
            else "通过 Grok CLI 设备授权登录，使用账号可用的模型与订阅额度。",
            style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用", Modifier.weight(1f))
            Checkbox(provider.enabled, { onEdit(provider.copyProvider(enabled = it)) })
        }
        OutlinedTextField(provider.name, { onEdit(provider.copyProvider(name = it)) },
            label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
        if (stored == null) Text("先添加并保存此提供商，再进入详情登录。")
        if (credential != null) {
            Text(credential.email ?: "已登录")
            credential.plan?.let { Text("订阅：$it", style = MaterialTheme.typography.bodySmall) }
        }
        when (status) {
            is AccountLoginStatus.Authorizing -> Text("正在等待浏览器授权…")
            is AccountLoginStatus.DeviceCode -> SelectionContainer {
                Text("请在浏览器完成授权。验证码：${status.userCode}\n${status.verificationUrl}")
            }
            is AccountLoginStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
            null -> Unit
        }
        Row {
            if (authorizing) {
                TextButton(onClick = { manager.cancelLogin(provider.id) }) { Text("取消登录") }
            } else {
                TextButton(enabled = stored != null, onClick = { manager.startLogin(provider.id, context) }) {
                    Text(if (credential == null) "浏览器登录" else "重新登录")
                }
                if (credential != null) TextButton(onClick = { scope.launch { manager.logout(provider.id) } }) {
                    Text("退出登录")
                }
            }
        }
        if (credential != null && stored != null) {
            TextButton(enabled = !quotaLoading, onClick = {
                quotaLoading = true
                val requested = credential
                scope.launch {
                    try {
                        val quota = providers.getProviderByType(stored).getQuota(stored)
                        val current = latestCredential
                        if (current != null && current.accountId == requested.accountId && current.email == requested.email) {
                            quotaText = formatAccountQuota(quota)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { quotaText = "额度查询失败，请稍后重试" }
                    finally { quotaLoading = false }
                }
            }) { Text(if (quotaLoading) "查询中…" else "查看／刷新额度") }
            quotaText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Text("登录凭据仅保存在本机，分享配置或恢复备份后需要重新登录。",
            style = MaterialTheme.typography.bodySmall)
    }
}

internal fun formatAccountQuota(quota: ProviderQuota): String = when (quota) {
    is ProviderQuota.Balance -> quota.value
    is ProviderQuota.UsageLimits -> buildString {
        if (quota.limitReached) appendLine("当前额度已达到上限")
        if (quota.windows.isEmpty()) append("服务商暂未返回额度信息")
        quota.windows.forEach { window ->
            val minutes = window.windowMinutes
            val label = when {
                minutes == null -> "额度窗口"
                minutes >= 1440 && minutes % 1440 == 0 -> "${minutes / 1440} 天"
                minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60} 小时"
                else -> "$minutes 分钟"
            }
            append("$label：已用 ${window.usedPercent.coerceIn(0.0, 100.0).roundToInt()}%")
            window.resetsAt?.let {
                val reset = java.time.Instant.ofEpochMilli(it.toEpochMilliseconds()).atZone(ZoneId.systemDefault())
                append("；重置于 ${DateTimeFormatter.ofPattern("MM-dd HH:mm").format(reset)}")
            }
            appendLine()
        }
    }.trim()
}
