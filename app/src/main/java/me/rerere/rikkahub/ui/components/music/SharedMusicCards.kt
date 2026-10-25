package me.rerere.rikkahub.ui.components.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.music.*
import org.koin.compose.koinInject

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
                color =
                    if (current.item.addedBy == "ta") MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                musicCoverUrl(current.song.coverUrl),
                null,
                Modifier.size(36.dp).clickable(onClick = onOpen),
            )
            Column(Modifier.weight(1f).padding(horizontal = 8.dp).clickable(onClick = onOpen)) {
                Text(current.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (current.item.addedBy == "ta") "TA 放给你 · ${current.item.note}"
                    else current.song.artist,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!compact)
                TextButton(
                    onClick = {
                        scope.launch { manager.reportFailure { manager.next(previous = true) } }
                    }
                ) {
                    Text("上一首")
                }
            TextButton(onClick = { scope.launch { manager.reportFailure { manager.toggle() } } }) {
                Text(if (current.state in listOf("playing", "loading")) "暂停" else "播放")
            }
            TextButton(onClick = { scope.launch { manager.reportFailure { manager.next() } } }) {
                Text("下一首")
            }
            TextButton(onClick = { scope.launch { manager.stop() } }) { Text("×") }
        }
        if (!compact) {
            var dragging by remember(current.song.songId) { mutableStateOf<Float?>(null) }
            Slider(
                value =
                    dragging
                        ?: current.positionMs
                            .toFloat()
                            .coerceIn(0f, current.durationMs.coerceAtLeast(1).toFloat()),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    val value = dragging
                    dragging = null
                    if (value != null)
                        scope.launch { manager.reportFailure { manager.seek(value.toLong()) } }
                },
                valueRange = 0f..current.durationMs.coerceAtLeast(1).toFloat(),
                modifier = Modifier.height(24.dp),
            )
            Text(
                "${musicTime(current.positionMs)} / ${musicTime(current.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Rendering old cards has no playback side effects. Only an explicit click may play one again. */
@Composable
fun SharedMusicCards(
    parts: List<UIMessagePart>,
    assistantId: String,
    manager: SharedMusicManager = koinInject(),
) {
    val cards =
        remember(parts) {
            parts
                .flatMap { if (it is UIMessagePart.Tool) it.output else listOf(it) }
                .mapNotNull { it.musicCard() }
                .distinct()
        }
    val scope = rememberCoroutineScope()
    var showPlayer by remember { mutableStateOf(false) }
    if (showPlayer)
        SharedMusicSheet(
            assistantId = assistantId,
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
        if (song != null)
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                    scope.launch {
                        manager.reportFailure { manager.play(song, assistantId, "") }
                        showPlayer = true
                    }
                }
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(musicCoverUrl(song.coverUrl), null, Modifier.size(56.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(song.title, style = MaterialTheme.typography.titleMedium)
                        Text(song.artist, style = MaterialTheme.typography.bodySmall)
                        val note = (card["note"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        if (note.isNotBlank()) Text(note)
                        (card["lines"] as? JsonArray)?.take(8)?.forEach { line ->
                            Text(
                                ((line as? JsonObject)?.get("text") as? JsonPrimitive)
                                    ?.contentOrNull
                                    .orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Text("♫")
                }
            }
    }
}
