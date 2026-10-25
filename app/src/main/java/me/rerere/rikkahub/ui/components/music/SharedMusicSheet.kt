package me.rerere.rikkahub.ui.components.music

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var server by remember { mutableStateOf(connection?.server.orEmpty()) }
    var tab by remember { mutableIntStateOf(initialTab) }
    var query by remember { mutableStateOf("") }
    var sourceId by remember { mutableStateOf<String?>(null) }
    var songs by remember { mutableStateOf(emptyList<MusicSong>()) }
    var sources by remember { mutableStateOf<JsonObject?>(null) }
    var qrKey by remember { mutableStateOf("") }
    var qrStatus by remember { mutableStateOf("") }
    var selectedLines by remember(playback?.song?.songId) { mutableStateOf(emptySet<Int>()) }
    val connected = account?.get("connected")?.jsonPrimitive?.booleanOrNull == true
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
    LaunchedEffect(connection?.token) {
        if (connection != null)
            manager.reportFailure {
                manager.refreshAccount()
                manager.refreshQueue()
            }
    }
    LaunchedEffect(connection?.token, connected, query, sourceId) {
        songs = emptyList()
        sources = null
        if (connected)
            manager.reportFailure {
                if (query.isNotBlank()) {
                    delay(400)
                    songs = manager.search(query)
                } else if (sourceId != null) songs = manager.sourceSongs(sourceId!!)
                else sources = manager.sources()
            }
    }
    LaunchedEffect(qrKey, connection?.token) {
        if (qrKey.isNotEmpty()) {
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < 180000) {
                delay(2000)
                val result = runCatching { manager.pollQr(qrKey) }.getOrNull() ?: continue
                qrStatus =
                    when (result["status"]?.jsonPrimitive?.contentOrNull) {
                        "success" -> "登录成功"
                        "scanned" -> "已扫码，请在网易云确认"
                        "expired" -> "二维码已过期，请重新生成"
                        else -> "等待扫码"
                    }
                if (qrStatus == "登录成功" || qrStatus.startsWith("二维码已过期")) break
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("分你一只耳机", style = MaterialTheme.typography.headlineSmall)
            Text(
                "和你的 AI 一起听网易云",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (connection == null) {
                Text("连接音乐服务后，用网易云扫码登录你自己的账号。")
                OutlinedTextField(
                    server,
                    { server = it },
                    label = { Text("音乐服务地址") },
                    placeholder = { Text("https://音乐服务域名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { action { manager.connect(server) } },
                    enabled = !busy && server.isNotBlank(),
                ) {
                    Text("连接")
                }
                Text(
                    "服务地址由西瓜瓣维护者或你的服务管理员提供。每个人的账号、播放列表和状态分别保存。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!connected) {
                Text("登录你的网易云账号", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        action {
                            val result = manager.qr()
                            qrKey = result["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            qrStatus = "等待扫码"
                        }
                    },
                    enabled = !busy,
                ) {
                    Text(if (qrKey.isEmpty()) "生成登录二维码" else "重新生成二维码")
                }
                if (qrKey.isNotEmpty()) MusicLoginQr(qrKey, qrStatus)
                Text(
                    "同一台手机：保存二维码，再到网易云「扫一扫」中从相册选择。请在网易云内确认授权。",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = {
                        action {
                            manager.forgetConnection()
                            server = ""
                        }
                    },
                    enabled = !busy,
                ) {
                    Text("断开此音乐服务")
                }
            } else {
                val profile = account?.get("profile") as? JsonObject
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile?.get("nickname")?.jsonPrimitive?.contentOrNull ?: "已登录网易云",
                        Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            action {
                                manager.logout()
                                qrKey = ""
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text("退出登录")
                    }
                }
                if (assistantId.isNotBlank())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("和当前角色一起听")
                            Text(
                                "让 TA 了解正在播放的歌曲，并能为你排歌、切歌",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            manager.isEnabled(assistantId),
                            { enabled -> action { manager.enableAssistant(assistantId, enabled) } },
                            enabled = !busy,
                        )
                    }
                playback?.let { current ->
                    MusicPlaybackControls(current, manager, onOpen = { tab = 2 })
                }
                PrimaryTabRow(tab) {
                    listOf("找歌", "播放列表", "歌词").forEachIndexed { index, title ->
                        Tab(tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                when (tab) {
                    0 -> {
                        OutlinedTextField(
                            query,
                            { query = it },
                            singleLine = true,
                            label = { Text("搜歌曲、歌手") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        sourceId?.let { source ->
                            Row {
                                TextButton(onClick = { sourceId = null }) { Text("返回歌单") }
                                TextButton(
                                    onClick = {
                                        action {
                                            manager.loadSource(source, assistantId, conversationId)
                                        }
                                    },
                                    enabled = !busy,
                                ) {
                                    Text("全部播放")
                                }
                            }
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            if (query.isBlank() && sourceId == null) {
                                item {
                                    Row {
                                        TextButton(onClick = { sourceId = "daily" }) {
                                            Text("每日推荐")
                                        }
                                        TextButton(onClick = { sourceId = "radar" }) {
                                            Text("私人雷达")
                                        }
                                    }
                                }
                                items((sources?.get("playlists") as? JsonArray).orEmpty()) {
                                    playlist ->
                                    val value = playlist.jsonObject
                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                value["name"]
                                                    ?.jsonPrimitive
                                                    ?.contentOrNull
                                                    .orEmpty()
                                            )
                                        },
                                        supportingContent = {
                                            Text(
                                                "${value["count"]?.jsonPrimitive?.contentOrNull.orEmpty()} 首"
                                            )
                                        },
                                        modifier =
                                            Modifier.clickable {
                                                sourceId =
                                                    value["playlistId"]
                                                        ?.jsonPrimitive
                                                        ?.contentOrNull
                                            },
                                    )
                                }
                            }
                            items(songs) { song ->
                                MusicSongRow(
                                    song,
                                    !busy,
                                    onPlay = {
                                        action { manager.play(song, assistantId, conversationId) }
                                    },
                                    onQueue = { action { manager.enqueue(song) } },
                                    onShare =
                                        onShare?.let { share ->
                                            {
                                                share(song, emptyList())
                                                onDismiss()
                                            }
                                        },
                                )
                            }
                        }
                    }
                    1 -> {
                        Row {
                            listOf("seq" to "顺序", "shuffle" to "随机", "one" to "单曲循环").forEach {
                                (mode, name) ->
                                FilterChip(
                                    queue.mode == mode,
                                    onClick = { action { manager.changeMode(mode) } },
                                    label = { Text(name) },
                                    enabled = !busy,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                        }
                        var filter by remember { mutableStateOf("") }
                        OutlinedTextField(
                            filter,
                            { filter = it },
                            label = { Text("在列表里找") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LazyColumn(Modifier.weight(1f)) {
                            items(
                                queue.items.filter {
                                    it.title.contains(filter, true) ||
                                        it.artist.contains(filter, true)
                                },
                                key = { it.id },
                            ) { item ->
                                Column {
                                    MusicSongRow(
                                        item.song(),
                                        !busy,
                                        onPlay = {
                                            action {
                                                manager.select(item, assistantId, conversationId)
                                            }
                                        },
                                        onShare =
                                            onShare?.let { share ->
                                                {
                                                    share(item.song(), emptyList())
                                                    onDismiss()
                                                }
                                            },
                                        note =
                                            (if (item.id == queue.currentId) "正在播放 · " else "") +
                                                if (item.addedBy == "ta") "TA：${item.note}" else "",
                                    )
                                    Row {
                                        TextButton(
                                            onClick = {
                                                action {
                                                    manager.move(
                                                        item.id,
                                                        (queue.items.indexOf(item) - 1)
                                                            .coerceAtLeast(0),
                                                    )
                                                }
                                            },
                                            enabled = !busy,
                                        ) {
                                            Text("上移")
                                        }
                                        TextButton(
                                            onClick = {
                                                action {
                                                    manager.move(
                                                        item.id,
                                                        (queue.items.indexOf(item) + 1)
                                                            .coerceAtMost(queue.items.lastIndex),
                                                    )
                                                }
                                            },
                                            enabled = !busy,
                                        ) {
                                            Text("下移")
                                        }
                                        TextButton(
                                            onClick = { action { manager.remove(item.id) } },
                                            enabled = !busy,
                                        ) {
                                            Text("移除")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        val current = playback
                        if (current == null) Text("选一首歌，一起听吧。")
                        else {
                            Text(
                                "${current.song.title} · ${current.song.artist}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (current.item.addedBy == "ta")
                                Text("TA：${current.item.note.ifBlank { "这首放给你" }}")
                            val lyricState =
                                androidx.compose.foundation.lazy.rememberLazyListState()
                            val active =
                                current.song.lyrics.indexOfLast {
                                    it.timeMs <= current.positionMs + 300
                                }
                            LaunchedEffect(current.song.songId, active) {
                                if (active >= 0 && !lyricState.isScrollInProgress)
                                    lyricState.animateScrollToItem((active - 2).coerceAtLeast(0))
                            }
                            LazyColumn(Modifier.weight(1f), state = lyricState) {
                                items(current.song.lyrics, key = { it.index }) { line ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable { action { manager.seek(line.timeMs) } }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (onShare != null)
                                            Checkbox(
                                                line.index in selectedLines,
                                                { selected ->
                                                    selectedLines =
                                                        if (selected && selectedLines.size < 8)
                                                            selectedLines + line.index
                                                        else selectedLines - line.index
                                                },
                                            )
                                        Column {
                                            Text(
                                                line.text,
                                                color =
                                                    if (line.index == active)
                                                        MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface,
                                            )
                                            if (line.trans.isNotBlank())
                                                Text(
                                                    line.trans,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                        }
                                    }
                                }
                            }
                            onShare?.let { share ->
                                Row {
                                    TextButton(
                                        onClick = {
                                            share(current.song, emptyList())
                                            onDismiss()
                                        }
                                    ) {
                                        Text("这首给 TA")
                                    }
                                    TextButton(
                                        onClick = {
                                            share(
                                                current.song,
                                                current.song.lyrics.filter {
                                                    it.index in selectedLines
                                                },
                                            )
                                            onDismiss()
                                        },
                                        enabled = selectedLines.isNotEmpty(),
                                    ) {
                                        Text("选几句给 TA 看（${selectedLines.size}/8）")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun MusicLoginQr(key: String, status: String) {
    val context = LocalContext.current
    val bitmap =
        remember(key) {
            val login =
                Uri.Builder()
                    .scheme("https")
                    .authority("music.163.com")
                    .appendPath("login")
                    .appendQueryParameter("codekey", key)
                    .build()
                    .toString()
            val matrix = QRCodeWriter().encode(login, BarcodeFormat.QR_CODE, 400, 400)
            Bitmap.createBitmap(400, 400, Bitmap.Config.RGB_565).apply {
                val pixels =
                    IntArray(400 * 400) { n ->
                        if (matrix[n % 400, n / 400]) android.graphics.Color.BLACK
                        else android.graphics.Color.WHITE
                    }
                setPixels(pixels, 0, 400, 0, 0, 400, 400)
            }
        }
    val scope = rememberCoroutineScope()
    var saveError by remember { mutableStateOf<String?>(null) }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri
            ->
            uri?.let {
                scope.launch {
                    saveError =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                            }
                                .exceptionOrNull()
                                ?.let { "保存失败，请重试" }
                        }
                }
            }
        }
    Image(bitmap.asImageBitmap(), contentDescription = "网易云登录二维码", modifier = Modifier.size(220.dp))
    Text(status)
    saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    TextButton(
        onClick = {
            if (Build.VERSION.SDK_INT >= 29)
                scope.launch {
                    saveError =
                        withContext(Dispatchers.IO) {
                            runCatching { saveMusicQrToGallery(context, bitmap) }
                                .exceptionOrNull()
                                ?.let { "保存失败，请重试" }
                        }
                }
            else save.launch("netease-login-qr.png")
        }
    ) {
        Text("保存二维码")
    }
}

@androidx.annotation.RequiresApi(29)
private fun saveMusicQrToGallery(context: Context, bitmap: Bitmap) {
    val resolver = context.contentResolver
    val values =
        ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "netease-login-${System.currentTimeMillis()}.png",
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WatermelonChat")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
    try {
        requireNotNull(resolver.openOutputStream(uri)).use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
}

@Composable
private fun MusicSongRow(
    song: MusicSong,
    enabled: Boolean,
    onPlay: () -> Unit,
    onQueue: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    note: String = "",
) {
    ListItem(
        leadingContent = { AsyncImage(musicCoverUrl(song.coverUrl), null, Modifier.size(44.dp)) },
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (note.isNotBlank()) Text(note, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = {
            Row {
                onShare?.let {
                    TextButton(
                        onClick = it,
                        enabled = enabled,
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        Text("分享")
                    }
                }
                onQueue?.let {
                    TextButton(
                        onClick = it,
                        enabled = enabled,
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        Text("＋")
                    }
                }
                TextButton(
                    onClick = onPlay,
                    enabled = enabled,
                    contentPadding = PaddingValues(4.dp),
                ) {
                    Text("播放")
                }
            }
        },
    )
}
