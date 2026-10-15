/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Delete01
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Checkbox
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import java.io.File

@Composable
fun SettingFilesPage(
    filesManager: FilesManager = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val folders = remember { listOf(FileFolders.UPLOAD) }

    var selectedFolder by remember { mutableStateOf(FileFolders.UPLOAD) }
    var pendingDelete by remember { mutableStateOf<List<ManagedFileEntity>>(emptyList()) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var deleting by remember { mutableStateOf(false) }
    val files by filesManager.observe(selectedFolder).collectAsState(initial = emptyList())

    val selectedFiles = files.filter { it.id in selectedIds }

    fun toggleSelection(file: ManagedFileEntity) {
        selectedIds = if (file.id in selectedIds) selectedIds - file.id else selectedIds + file.id
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    BackHandler(enabled = selectionMode || deleting) {
        if (!deleting) exitSelection()
    }

    if (pendingDelete.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { if (!deleting) pendingDelete = emptyList() },
            title = { Text("删除 ${pendingDelete.size} 个文件？") },
            text = {
                Text("共 ${formatBytes(pendingDelete.sumOf { it.sizeBytes })}。删除后无法恢复，" +
                    "聊天记录中引用这些文件的图片、录音等附件将无法打开。")
            },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    val targets = pendingDelete
                    deleting = true
                    scope.launch {
                        val failedIds = mutableSetOf<Long>()
                        try {
                            for (file in targets) {
                                val ok = try {
                                    filesManager.delete(file.id, deleteFromDisk = true)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    false
                                }
                                if (!ok) failedIds.add(file.id)
                            }
                            selectedIds = failedIds
                            selectionMode = failedIds.isNotEmpty()
                            pendingDelete = emptyList()
                            val deleted = targets.size - failedIds.size
                            toaster.show(if (failedIds.isEmpty()) "已删除 $deleted 个文件"
                                else "已删除 $deleted 个文件，${failedIds.size} 个失败，可重新选择删除")
                        } finally {
                            deleting = false
                        }
                    }
                }) {
                    Text(if (deleting) "正在删除…" else stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { pendingDelete = emptyList() }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_files_page_title)) },
                navigationIcon = {
                    if (selectionMode) {
                        TextButton(enabled = !deleting, onClick = { exitSelection() }) { Text("取消") }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    if (files.isNotEmpty() && !selectionMode) {
                        TextButton(onClick = { selectionMode = true }) { Text("多选") }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            FolderRow(
                folders = folders,
                selectedFolder = selectedFolder,
                onFolderSelected = { selectedFolder = it }
            )

            if (selectionMode) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("已选 ${selectedFiles.size} 个 · ${formatBytes(selectedFiles.sumOf { it.sizeBytes })}",
                        style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val allSelected = files.isNotEmpty() && selectedFiles.size == files.size
                        TextButton(enabled = !deleting && files.isNotEmpty(), onClick = {
                            selectedIds = if (allSelected) emptySet() else files.map { it.id }.toSet()
                        }) { Text(if (allSelected) "取消全选" else "全选") }
                        TextButton(enabled = !deleting && selectedFiles.isNotEmpty(), onClick = {
                            pendingDelete = selectedFiles.toList()
                        }) { Text("删除所选") }
                    }
                }
            }

            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.setting_files_page_no_files))
                }
            } else {
                LazyVerticalStaggeredGrid(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    state = gridState,
                    columns = StaggeredGridCells.Fixed(2)
                ) {
                    items(files, key = { it.id }) { file ->
                        FileItem(
                            file = file,
                            fileOnDisk = filesManager.getFile(file),
                            selectionMode = selectionMode,
                            selected = file.id in selectedIds,
                            onClick = { if (selectionMode) toggleSelection(file) },
                            onLongClick = {
                                selectionMode = true
                                toggleSelection(file)
                            },
                            onDelete = { pendingDelete = listOf(file) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    folders: List<String>,
    selectedFolder: String,
    onFolderSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        folders.forEach { folder ->
            FilterChip(
                selected = selectedFolder == folder,
                onClick = { onFolderSelected(folder) },
                label = { Text(folderDisplayName(folder)) }
            )
        }
    }
}

@Composable
private fun folderDisplayName(folder: String): String = when (folder) {
    FileFolders.UPLOAD -> stringResource(R.string.setting_files_page_folder_upload)
    else -> folder
}

@Composable
private fun FileItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (file.mimeType.startsWith("image/")) {
                    AsyncImage(
                        model = fileOnDisk,
                        contentDescription = file.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = HugeIcons.Image02,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                } else {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.setting_files_page_delete_content_description)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.mimeType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatBytes(file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1fKB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1fMB", mb)
    val gb = mb / 1024.0
    return String.format("%.1fGB", gb)
}
