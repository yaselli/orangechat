package me.rerere.rikkahub.ui.components.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.composables.icons.lucide.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.music.*
import org.koin.compose.koinInject

@Composable
internal fun MusicCover(url: String, modifier: Modifier = Modifier, round: Boolean = false) {
    Box(
        modifier
            .clip(if (round) CircleShape else RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Lucide.Disc,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .3f),
        )
        AsyncImage(
            musicCoverUrl(url),
            null,
            Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
internal fun MusicIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick, enabled = enabled) { Icon(icon, label, Modifier.size(21.dp)) }
}

@Composable
fun SharedMusicMiniBar(
    assistantId: String,
    onOpen: () -> Unit,
    manager: SharedMusicManager = koinInject(),
) {
    val playback by manager.playback.collectAsStateWithLifecycle()
    playback
        ?.takeIf { it.assistantId == assistantId }
        ?.let { current ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(28.dp),
                color =
                    if (current.item.addedBy == "ta")
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = .8f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .92f),
                tonalElevation = 2.dp,
            ) {
                MusicPlaybackControls(current, manager, onOpen, compact = true)
            }
        }
}

@Composable
internal fun MusicPlaybackControls(
    current: MusicPlayback,
    manager: SharedMusicManager,
    onOpen: () -> Unit,
    compact: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    fun action(block: suspend () -> Unit) {
        scope.launch { manager.reportFailure(block) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 8.dp else 4.dp)) {
        if (compact) {
            Row(Modifier.heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                MusicCover(
                    current.song.coverUrl,
                    Modifier.size(40.dp).clickable(onClick = onOpen),
                    round = true,
                )
                Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(start = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (current.item.addedBy == "ta")
                            Icon(
                                Lucide.Headphones,
                                "TA 放给你",
                                Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        Text(
                            current.song.title,
                            Modifier.padding(
                                start = if (current.item.addedBy == "ta") 4.dp else 0.dp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        current.song.artist,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MusicIcon(
                    if (current.state in listOf("playing", "loading")) Lucide.Pause
                    else Lucide.Play,
                    "播放或暂停",
                ) {
                    action { manager.toggle() }
                }
                MusicIcon(Lucide.SkipForward, "下一首") { action { manager.next() } }
                MusicIcon(Lucide.ListMusic, "打开播放器", onClick = onOpen)
                MusicIcon(Lucide.X, "停止一起听") { action { manager.stop() } }
            }
        } else {
            var dragging by remember(current.item.id) { mutableStateOf<Float?>(null) }
            Slider(
                value =
                    dragging
                        ?: current.positionMs
                            .toFloat()
                            .coerceIn(0f, current.durationMs.coerceAtLeast(1).toFloat()),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    val at = dragging
                    dragging = null
                    if (at != null) action { manager.seek(at.toLong()) }
                },
                valueRange = 0f..current.durationMs.coerceAtLeast(1).toFloat(),
                modifier = Modifier.height(28.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    musicTime(current.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    musicTime(current.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MusicIcon(Lucide.SkipBack, "上一首") {
                    action { manager.next(previous = true) }
                }
                FilledIconButton(
                    onClick = { action { manager.toggle() } },
                    modifier = Modifier.size(60.dp),
                    shape = CircleShape,
                ) {
                    if (current.state == "loading")
                        CircularProgressIndicator(
                            Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    else
                        Icon(
                            if (current.state == "playing") Lucide.Pause else Lucide.Play,
                            "播放或暂停",
                            Modifier.size(30.dp),
                        )
                }
                MusicIcon(Lucide.SkipForward, "下一首") { action { manager.next() } }
            }
        }
    }
}

/** A stylized vinyl record, not a fabricated audio waveform. */
@Composable
internal fun MusicVinylArtwork(song: MusicSong, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxHeight(.88f).aspectRatio(1f).align(Alignment.CenterEnd)) {
            val radius = size.minDimension / 2
            drawCircle(Color(0xFF292830), radius)
            for (i in 1..14) drawCircle(
                Color.White.copy(alpha = .08f),
                radius * (.3f + i * .045f),
                style = Stroke(1.dp.toPx()),
            )
        }
        MusicCover(
            song.coverUrl,
            Modifier.fillMaxHeight(.34f)
                .aspectRatio(1f)
                .align(Alignment.CenterEnd)
                .offset(x = (-24).dp),
            round = true,
        )
        MusicCover(
            song.coverUrl,
            Modifier.fillMaxHeight().aspectRatio(1f).align(Alignment.CenterStart),
        )
    }
}

/** Rendering old cards has no playback side effects. Only an explicit click may play one again. */
@Composable
fun SharedMusicCards(
    parts: List<UIMessagePart>,
    assistantId: String,
    manager: SharedMusicManager = koinInject(),
    conversationId: String = "",
) {
    val cards =
        remember(parts) {
            parts
                .flatMap { if (it is UIMessagePart.Tool) it.output else listOf(it) }
                .mapNotNull { it.musicCard() }
                .distinct()
        }
    val playback by manager.playback.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showPlayer by remember { mutableStateOf(false) }
    if (showPlayer)
        SharedMusicSheet(
            assistantId = assistantId,
            conversationId = conversationId,
            initialTab = 2,
            onDismiss = { showPlayer = false },
        )
    cards.forEach { card ->
        val song =
            remember(card) {
                runCatching {
                    Json { ignoreUnknownKeys = true }.decodeFromJsonElement<MusicSong>(card)
                }
                    .getOrNull()
            }
        if (song != null) {
            val current = playback?.takeIf {
                it.song.songId == song.songId &&
                    it.song.platform == song.platform &&
                    it.assistantId == assistantId
            }
            Card(
                onClick = {
                    scope.launch {
                        manager.reportFailure { manager.play(song, assistantId, conversationId) }
                        showPlayer = true
                    }
                },
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(vertical = 6.dp),
                shape = RoundedCornerShape(26.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            musicPlatformName(song.platform),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "分你一只耳机",
                            fontStyle = FontStyle.Italic,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    MusicVinylArtwork(song, Modifier.width(216.dp).height(136.dp))
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    (card["note"] as? JsonPrimitive)
                        ?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    (card["lines"] as? JsonArray)?.take(8)?.forEach { line ->
                        Text(
                            line.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (current != null)
                        LinearProgressIndicator(
                            progress = {
                                (current.positionMs.toFloat() / current.durationMs.coerceAtLeast(1))
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (current != null) musicTime(current.positionMs) else "点击播放",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            Lucide.CirclePlay,
                            "播放歌曲",
                            Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MusicSongRow(
    song: MusicSong,
    enabled: Boolean,
    onPlay: () -> Unit,
    onQueue: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    note: String = "",
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f)
                else Color.Transparent
            )
            .clickable(enabled = enabled, onClick = onPlay)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicCover(song.coverUrl, Modifier.size(48.dp))
        Column(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                song.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (note.isNotBlank())
                Text(
                    note,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
        }
        onShare?.let { MusicIcon(Lucide.Send, "分享歌卡", enabled, it) }
        onQueue?.let { MusicIcon(Lucide.ListPlus, "加入下一首", enabled, it) }
        trailing?.invoke()
    }
}
