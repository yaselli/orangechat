package me.rerere.rikkahub.ui.components.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.music.*

@Composable
internal fun MusicLibraryPage(
    manager: SharedMusicManager,
    assistantId: String,
    conversationId: String,
    busy: Boolean,
    action: (suspend () -> Unit) -> Unit,
    onShare: ((MusicSong, List<MusicLyric>) -> Unit)?,
    modifier: Modifier,
) {
    var query by remember { mutableStateOf("") }
    var sourceId by remember { mutableStateOf<String?>(null) }
    var songs by remember { mutableStateOf(emptyList<MusicSong>()) }
    var sources by remember { mutableStateOf<JsonObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    val connection = manager.connection.value
    val remoteQuery = if (sourceId == null) query else ""
    LaunchedEffect(connection?.token, connection?.platform, remoteQuery, sourceId) {
        songs = emptyList()
        loading = true
        try {
            manager.reportFailure {
                if (remoteQuery.isNotBlank()) {
                    delay(400)
                    songs = manager.search(remoteQuery)
                } else if (sourceId != null) songs = manager.sourceSongs(sourceId!!)
                else sources = manager.sources()
            }
        } finally {
            loading = false
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            query,
            { query = it },
            placeholder = { Text(if (sourceId == null) "搜歌、歌手" else "在这个歌单里找") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
        )
        if (sourceId != null)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MusicIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回推荐") {
                    sourceId = null
                    query = ""
                }
                Spacer(Modifier.weight(1f))
                if (sourceId!!.all { it.isDigit() })
                    TextButton(
                        onClick = { action { manager.importPlaylist(sourceId!!) } },
                        enabled = !busy,
                    ) {
                        Text("加入列表")
                    }
                FilledTonalButton(
                    onClick = {
                        action { manager.loadSource(sourceId!!, assistantId, conversationId) }
                    },
                    enabled = !busy,
                ) {
                    Text("全部播放")
                }
            }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (query.isBlank() && sourceId == null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        (sources?.get("specials") as? JsonArray).orEmpty().take(2).forEach { value
                            ->
                            val card = value.jsonObject
                            MusicRecommendationCard(card, Modifier.weight(1f)) {
                                sourceId = card["id"]?.jsonPrimitive?.contentOrNull
                            }
                        }
                    }
                }
                item {
                    Text(
                        "我的音乐收藏",
                        Modifier.padding(top = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items((sources?.get("playlists") as? JsonArray).orEmpty()) { value ->
                    val playlist = value.jsonObject
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                sourceId =
                                    playlist["playlistId"]?.jsonPrimitive?.contentOrNull
                                        ?: playlist["id"]?.jsonPrimitive?.contentOrNull
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MusicCover(
                            playlist["coverUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            Modifier.size(48.dp),
                        )
                        Text(
                            playlist["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${playlist["count"]?.jsonPrimitive?.contentOrNull ?: playlist["trackCount"]?.jsonPrimitive?.contentOrNull.orEmpty()} 首",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val visible =
                if (sourceId == null) songs
                else
                    songs.filter {
                        it.title.contains(query, true) || it.artist.contains(query, true)
                    }
            items(visible, key = { it.songId }) { song ->
                MusicSongRow(
                    song,
                    !busy,
                    onPlay = { action { manager.play(song, assistantId, conversationId) } },
                    onQueue = { action { manager.enqueue(song) } },
                    onShare = onShare?.let { share -> { share(song, emptyList()) } },
                )
            }
            if (!loading && visible.isEmpty() && (sourceId != null || query.isNotBlank()))
                item {
                    Text(
                        "还没有找到歌曲，换个关键词试试。",
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}

@Composable
private fun MusicRecommendationCard(card: JsonObject, modifier: Modifier, onClick: () -> Unit) {
    val title = card["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    Column(
        modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp))) {
            MusicCover(
                card["coverUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                Modifier.matchParentSize(),
            )
            Box(
                Modifier.matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = .55f))
                        )
                    )
            )
            if (card["id"]?.jsonPrimitive?.contentOrNull == "daily") {
                Text(
                    java.time.LocalDate.now().dayOfMonth.toString().padStart(2, '0'),
                    Modifier.align(Alignment.TopStart).padding(16.dp),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                )
            }
            Icon(
                Icons.Outlined.PlayCircle,
                title,
                Modifier.align(Alignment.BottomEnd).padding(12.dp).size(32.dp),
                tint = Color.White,
            )
        }
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            card["line"]?.jsonPrimitive?.contentOrNull?.ifBlank { "为你挑选的音乐" } ?: "为你挑选的音乐",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
