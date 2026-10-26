package me.rerere.rikkahub.ui.components.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.music.*

@Composable
internal fun MusicLyricsPage(
    current: MusicPlayback?,
    manager: SharedMusicManager,
    onShare: ((MusicSong, List<MusicLyric>) -> Unit)?,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var selecting by
        remember(current?.song?.songId, current?.song?.platform) { mutableStateOf(false) }
    var selected by
        remember(current?.song?.songId, current?.song?.platform) { mutableStateOf(emptySet<Int>()) }
    val scroll = rememberLazyListState()
    val active =
        if (current?.trial == false)
            current.song.lyrics.indexOfLast { it.timeMs <= current.positionMs + 300 }
        else -1
    LaunchedEffect(current?.song?.songId, active, selecting) {
        if (active >= 0 && !selecting && !scroll.isScrollInProgress)
            scroll.animateScrollToItem((active - 2).coerceAtLeast(0))
    }
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (current == null) {
            Spacer(Modifier.weight(1f))
            Icon(
                Lucide.Headphones,
                null,
                Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = .4f),
            )
            Text(
                "选一首歌，一起听吧。",
                Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
        } else {
            Text(
                current.song.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                current.song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (current.item.addedBy == "ta")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .4f),
                ) {
                    Text(
                        "TA：${current.item.note.ifBlank { "这首放给你" }}",
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            if (current.trial) Text("当前为试听片段，歌词可浏览与分享", style = MaterialTheme.typography.labelSmall)
            if (current.song.lyrics.isEmpty())
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        MusicVinylArtwork(current.song, Modifier.width(240.dp).height(150.dp))
                        Text("纯音乐，或暂时没有歌词", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            else
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = scroll,
                    contentPadding = PaddingValues(vertical = 24.dp),
                ) {
                    itemsIndexed(current.song.lyrics, key = { _, line -> line.index }) {
                        lineIndex,
                        line ->
                        val highlighted = current.song.lyrics.getOrNull(active)?.index == line.index
                        val distance = kotlin.math.abs(lineIndex - active)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    if (selecting) {
                                        selected =
                                            if (line.index in selected) selected - line.index
                                            else if (selected.size < 8) selected + line.index
                                            else selected
                                    } else if (!current.trial)
                                        scope.launch {
                                            manager.reportFailure { manager.seek(line.timeMs) }
                                        }
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selecting) Checkbox(line.index in selected, null)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    line.text,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight =
                                        if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                                    color =
                                        if (line.index in selected && selecting)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(
                                                alpha =
                                                    if (highlighted) 1f
                                                    else if (distance <= 2) .48f else .26f
                                            ),
                                )
                                if (line.trans.isNotBlank())
                                    Text(
                                        line.trans,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color =
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = if (highlighted) .9f else .5f
                                            ),
                                    )
                            }
                        }
                    }
                }
            MusicPlaybackControls(current, manager, onOpen = {})
            if (onShare != null)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(
                        onClick = {
                            if (selecting) {
                                selecting = false
                                selected = emptySet()
                            } else onShare(current.song, emptyList())
                        }
                    ) {
                        Text(if (selecting) "取消选择" else "这首给 TA")
                    }
                    TextButton(
                        onClick = {
                            if (!selecting) selecting = true
                            else
                                onShare(
                                    current.song,
                                    current.song.lyrics.filter { it.index in selected },
                                )
                        },
                        enabled = !selecting || selected.isNotEmpty(),
                    ) {
                        Text(if (selecting) "分享这 ${selected.size} 句" else "选几句给 TA 看")
                    }
                }
        }
    }
}
