package me.rerere.rikkahub.ui.components.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.music.*

@Composable
internal fun MusicQueuePage(
    manager: SharedMusicManager,
    queue: MusicQueue,
    assistantId: String,
    conversationId: String,
    busy: Boolean,
    action: (suspend () -> Unit) -> Unit,
    onShare: ((MusicSong, List<MusicLyric>) -> Unit)?,
    modifier: Modifier,
) {
    var query by remember { mutableStateOf("") }
    val scroll = rememberLazyListState()
    LaunchedEffect(Unit) {
        val at = queue.items.indexOfFirst { it.id == queue.currentId }
        if (at >= 0) scroll.scrollToItem(at)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Up next", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "你们俩的播放列表 · ${queue.items.size} 首",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            listOf(
                    Triple("seq", Icons.Outlined.QueueMusic, "顺序播放"),
                    Triple("shuffle", Icons.Outlined.Shuffle, "随机播放"),
                    Triple("one", Icons.Outlined.RepeatOne, "单曲循环"),
                )
                .forEach { (mode, icon, label) ->
                    IconToggleButton(
                        queue.mode == mode,
                        onCheckedChange = { action { manager.changeMode(mode) } },
                        enabled = !busy,
                    ) {
                        Icon(icon, label, Modifier.size(20.dp))
                    }
                }
        }
        OutlinedTextField(
            query,
            { query = it },
            placeholder = { Text("在列表里找") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        )
        if (queue.items.isEmpty())
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "把喜欢的歌排进来，也留个位置给 TA。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        else
            LazyColumn(Modifier.weight(1f), state = scroll) {
                items(
                    queue.items.filter {
                        it.title.contains(query, true) || it.artist.contains(query, true)
                    },
                    key = { it.id },
                ) { item ->
                    var menu by remember(item.id) { mutableStateOf(false) }
                    MusicSongRow(
                        item.song(),
                        !busy,
                        onPlay = { action { manager.select(item, assistantId, conversationId) } },
                        selected = item.id == queue.currentId,
                        note =
                            if (item.addedBy == "ta") "TA：${item.note.ifBlank { "这首放给你" }}" else "",
                        onShare = onShare?.let { share -> { share(item.song(), emptyList()) } },
                        trailing = {
                            Box {
                                MusicIcon(Icons.Outlined.MoreVert, "管理这首歌", !busy) { menu = true }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("上移") },
                                        onClick = {
                                            menu = false
                                            action {
                                                manager.move(
                                                    item.id,
                                                    (queue.items.indexOf(item) - 1).coerceAtLeast(
                                                        0
                                                    ),
                                                )
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("下移") },
                                        onClick = {
                                            menu = false
                                            action {
                                                manager.move(
                                                    item.id,
                                                    (queue.items.indexOf(item) + 1).coerceAtMost(
                                                        queue.items.lastIndex
                                                    ),
                                                )
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("从列表移除") },
                                        onClick = {
                                            menu = false
                                            action { manager.remove(item.id) }
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
    }
}
