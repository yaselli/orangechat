package me.rerere.rikkahub.ui.components.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import me.rerere.rikkahub.ui.theme.MaterialModalBottomSheet as ModalBottomSheet
import me.rerere.rikkahub.ui.theme.MaterialListItem as ListItem
import me.rerere.rikkahub.ui.theme.MaterialSurface as Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.music.*
import org.koin.compose.koinInject

@Composable
fun SharedMusicSheet(
    assistantId: String = "",
    conversationId: String = "",
    onDismiss: () -> Unit,
    initialTab: Int = 0,
    onShare: ((MusicSong, List<MusicLyric>) -> Unit)? = null,
    manager: SharedMusicManager = koinInject(),
) {
    val connection by manager.connection.collectAsStateWithLifecycle()
    val account by manager.account.collectAsStateWithLifecycle()
    val playback by manager.playback.collectAsStateWithLifecycle()
    val queue by manager.queue.collectAsStateWithLifecycle()
    val error by manager.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    val connected = account?.get("connected")?.jsonPrimitive?.booleanOrNull == true
    val current = playback?.takeIf { assistantId.isBlank() || it.assistantId == assistantId }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                manager.reportFailure(block)
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(connection?.token, connection?.platform) {
        if (connection != null)
            manager.reportFailure {
                manager.refreshAccount()
                manager.refreshQueue()
            }
    }
    val share: ((MusicSong, List<MusicLyric>) -> Unit)? = onShare?.let { callback ->
        { song, lines ->
            callback(song, lines)
            onDismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.93f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Lucide.Headphones,
                    null,
                    Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("分你一只耳机", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (connected)
                            "${musicPlatformName(connection?.platform ?: "netease")} · 和 TA 一起听"
                        else "两个人，一人一只耳机",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MusicIcon(
                    if (tab == 3) Lucide.Library else Lucide.Settings,
                    if (tab == 3) "返回音乐" else "一起听设置",
                ) {
                    tab = if (tab == 3) 0 else 3
                }
                MusicIcon(Lucide.ChevronDown, "收起播放器", onClick = onDismiss)
            }
            error?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        it,
                        Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!connected || tab == 3) {
                MusicAccountPage(
                    manager,
                    assistantId,
                    connection,
                    account,
                    busy,
                    ::action,
                    Modifier.weight(1f),
                )
            } else {
                PrimaryTabRow(tab) {
                    listOf("为你推荐", "待播列表", "正在唱").forEachIndexed { index, title ->
                        Tab(tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                when (tab) {
                    0 ->
                        MusicLibraryPage(
                            manager,
                            assistantId,
                            conversationId,
                            busy,
                            ::action,
                            share,
                            Modifier.weight(1f),
                        )
                    1 ->
                        MusicQueuePage(
                            manager,
                            queue,
                            assistantId,
                            conversationId,
                            busy,
                            ::action,
                            share,
                            Modifier.weight(1f),
                        )
                    2 -> MusicLyricsPage(current, manager, share, Modifier.weight(1f))
                }
                if (tab != 2 && current != null) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        MusicPlaybackControls(
                            current,
                            manager,
                            onOpen = { tab = 2 },
                            compact = true,
                        )
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun MusicAccountPage(
    manager: SharedMusicManager,
    assistantId: String,
    connection: MusicConnection?,
    account: JsonObject?,
    busy: Boolean,
    action: (suspend () -> Unit) -> Unit,
    modifier: Modifier,
) {
    var server by remember(connection?.server) { mutableStateOf(connection?.server.orEmpty()) }
    var accessKey by remember { mutableStateOf("") }
    var qrKey by remember { mutableStateOf("") }
    var qrImage by remember { mutableStateOf("") }
    var qrStatus by remember { mutableStateOf("") }
    val platform = connection?.platform ?: "netease"
    val connected = account?.get("connected")?.jsonPrimitive?.booleanOrNull == true
    LaunchedEffect(platform) {
        qrKey = ""
        qrImage = ""
        qrStatus = ""
    }
    LaunchedEffect(qrKey, platform, connection?.token) {
        if (qrKey.isBlank()) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 180_000) {
            delay(2_000)
            val result = runCatching { manager.pollQr(qrKey) }.getOrNull() ?: continue
            val state = result["status"]?.jsonPrimitive?.contentOrNull
            qrStatus =
                when (state) {
                    "success" -> "登录成功"
                    "scanned" -> "已扫码，请在${if (platform == "qq") "QQ" else "网易云"}确认"
                    "expired" -> "二维码已过期，请重新生成"
                    else -> "等待扫码"
                }
            if (state in listOf("success", "expired")) break
        }
        if (qrStatus == "等待扫码" || qrStatus.startsWith("已扫码")) qrStatus = "二维码已过期，请重新生成"
    }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (connection == null) {
            Text("连接自己的音乐服务", style = MaterialTheme.typography.titleMedium)
            Text(
                "填写已部署的服务地址，接着扫码登录你的音乐账号。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                server,
                { server = it },
                label = { Text("音乐服务地址") },
                placeholder = { Text("https://音乐服务域名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            )
            OutlinedTextField(
                accessKey,
                { accessKey = it },
                label = { Text("音乐服务访问口令") },
                visualTransformation =
                    androidx.compose.ui.text.input.PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            )
            Button(
                onClick = {
                    action {
                        manager.connect(server, accessKey)
                        accessKey = ""
                    }
                },
                enabled = !busy && server.isNotBlank() && accessKey.length >= 16,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("连接音乐服务")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("netease", "qq").forEach { value ->
                    FilterChip(
                        platform == value,
                        onClick = { action { manager.switchPlatform(value) } },
                        enabled = !busy,
                        label = { Text(if (value == "qq") "QQ 音乐 · 实验" else "网易云音乐") },
                    )
                }
            }
            if (platform == "qq")
                Text(
                    "QQ 播放尚未验证通过，微信账号登录暂未接入。这轮建议先使用网易云。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (!connected) {
                Text(
                    "登录你的${musicPlatformName(platform)}账号",
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(
                    onClick = {
                        action {
                            val qr = manager.qr()
                            qrImage = qr["qrImage"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            qrKey = qr["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            qrStatus = "等待扫码"
                        }
                    },
                    enabled = !busy,
                ) {
                    Text(if (qrKey.isBlank()) "生成登录二维码" else "重新生成二维码")
                }
                if (qrKey.isNotBlank()) MusicLoginQr(qrKey, qrStatus, platform, qrImage)
                Text(
                    if (platform == "qq") "用手机 QQ 扫码授权；同一台手机可保存二维码，再从 QQ 扫一扫的相册选择。"
                    else "同一台手机可先保存二维码，再从网易云「扫一扫」的相册选择，并确认授权。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                val nickname =
                    (account?.get("profile") as? JsonObject)
                        ?.get("nickname")
                        ?.jsonPrimitive
                        ?.contentOrNull
                ListItem(
                    headlineContent = { Text(nickname ?: "已登录${musicPlatformName(platform)}") },
                    leadingContent = { Icon(Lucide.CircleUserRound, null) },
                    trailingContent = {
                        TextButton(
                            onClick = {
                                action {
                                    manager.logout()
                                    qrKey = ""
                                }
                            },
                            enabled = !busy,
                        ) {
                            Text("退出")
                        }
                    },
                )
                if (assistantId.isNotBlank()) {
                    MusicSettingRow(
                        "和当前角色一起听",
                        "让 TA 了解歌曲、进度和歌词，也可以为你排歌。",
                        manager.isEnabled(assistantId),
                        !busy,
                    ) { value ->
                        action { manager.enableAssistant(assistantId, value) }
                    }
                    MusicSettingRow(
                        "听到喜欢的歌词时说一句",
                        "开启会额外调用聊天模型。每首最多判断一次，至少间隔五分钟；TA 也可以安静听。遵循主动消息的免打扰时段。",
                        manager.isCommentaryEnabled(assistantId),
                        !busy && manager.isEnabled(assistantId),
                    ) { value ->
                        action { manager.enableCommentary(assistantId, value) }
                    }
                }
            }
            TextButton(onClick = { action { manager.forgetConnection() } }, enabled = !busy) {
                Text("断开此音乐服务")
            }
        }
    }
}

@Composable
private fun MusicSettingRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked, onChecked, enabled = enabled)
    }
}
