package me.rerere.rikkahub.ui.pages.mediacreation

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import me.rerere.rikkahub.ui.theme.MaterialAlertDialog as AlertDialog
import me.rerere.rikkahub.ui.theme.MaterialCard as Card
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationParameter
import me.rerere.mediagen.provider.capabilities
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.components.sketch.SketchDialog
import me.rerere.rikkahub.ui.hooks.heroContainer
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.VideoPlayerDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCreationSessionsPage(vm: MediaCreationSessionsVM = koinViewModel()) {
    val nav = LocalNavController.current
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<MediaCreationSession?>(null) }
    var renaming by remember { mutableStateOf<MediaCreationSession?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("图片与视频创作") }, navigationIcon = { BackButton() }, actions = {
            TextButton(onClick = { nav.navigate(Screen.SettingMedia) }) { Text("提供商") }
        })
    }, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = {
            vm.newSession { nav.navigate(Screen.MediaCreation(it.toString())) }
        }) { Text("新建创作") }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TextButton(onClick = { nav.navigate(Screen.ImageGen) }) { Text("原生图工具与历史记录") }
            }
            if (sessions == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (sessions?.isEmpty() == true) item { Text("添加媒体提供商后，新建创作即可生成图片或视频。") }
            items(sessions.orEmpty(), key = { it.id.toString() }) { session ->
                Card(Modifier.fillMaxWidth()
                    .heroContainer(mediaCreationHeroKey(session.id.toString()), RoundedCornerShape(12.dp))
                    .clickable { nav.navigate(Screen.MediaCreation(session.id.toString())) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(session.title.ifBlank { "新的创作" }, style = MaterialTheme.typography.titleMedium)
                        Text("${session.nodeCount} 项 · ${session.activeCount} 项进行中")
                        session.cover?.let { output ->
                            MediaThumbnail(vm.resolve(output.path), output.mimeType.startsWith("video/"),
                                Modifier.fillMaxWidth().height(140.dp), output.posterPath?.let(vm::resolve))
                        }
                        Row {
                            TextButton(onClick = { renaming = session }) { Text("重命名") }
                            TextButton(onClick = { deleting = session }) { Text("删除") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }
    deleting?.let { session ->
        ConfirmMediaDelete("删除整个创作及其文件？进行中的等待会停止，已提交给服务商的任务无法撤回。",
            onDismiss = { deleting = null }, onConfirm = { vm.delete(session.id); deleting = null })
    }
    renaming?.let { session ->
        RenameSessionDialog(session.title, onDismiss = { renaming = null }, onConfirm = {
            vm.rename(session.id, it); renaming = null
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCreationPage(id: String) {
    val vm: MediaCreationVM = koinViewModel(parameters = { parametersOf(id) })
    val nav = LocalNavController.current
    val toaster = LocalToaster.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val nodes = vm.nodes.collectAsLazyPagingItems()
    val selectedNodes by vm.selectedNodes.collectAsStateWithLifecycle()
    val heroCorner by LocalNavAnimatedContentScope.current.transition.animateDp(label = "mediaCorner") {
        if (it == EnterExitState.Visible) 0.dp else 12.dp
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var sketching by remember { mutableStateOf<MediaSketchRequest?>(null) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    BackHandler(enabled = selectedNodes != null) { vm.endSelection() }
    var models by remember { mutableStateOf(false) }
    var parameters by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var uploadRequired by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MediaCreationRecord?>(null) }
    var preview by remember { mutableStateOf<MediaCreationOutput?>(null) }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        vm.importAssets(it, MediaCreationAssetType.IMAGE, ImageRole.REFERENCE)
    }
    val videos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        vm.importAssets(it, MediaCreationAssetType.VIDEO, ImageRole.REFERENCE)
    }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is MediaCreationEvent.Error -> toaster.show(event.message, type = ToastType.Error)
                MediaCreationEvent.UploadNotConfigured -> uploadRequired = true
                MediaCreationEvent.SessionGone -> nav.popBackStack()
            }
        }
    }
    Scaffold(modifier = Modifier.heroContainer(mediaCreationHeroKey(id), RoundedCornerShape(heroCorner)), topBar = {
        TopAppBar(title = {
            Text(selectedNodes?.let { "已选择 ${it.size} 项" } ?: session?.title?.ifBlank { "新的创作" } ?: "加载中",
                Modifier.clickable(enabled = selectedNodes == null) { renaming = true }, maxLines = 1)
        }, navigationIcon = { BackButton() }, actions = {
            if (selectedNodes == null) {
                TextButton(onClick = { vm.startSelection() }, enabled = nodes.itemCount > 0) { Text("多选") }
                TextButton(onClick = { nav.navigate(Screen.SettingMedia) }) { Text("提供商") }
            } else {
                TextButton(onClick = vm::endSelection, enabled = !exporting) { Text("完成") }
            }
        })
    }) { padding ->
        // One lazy timeline owns scrolling; the composer scrolls with the latest records on small screens.
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selectedNodes != null) item("selection") {
                Column {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = vm::selectAll, enabled = !exporting) { Text("全选") }
                        TextButton(onClick = vm::clearSelection, enabled = !exporting) { Text("清空选择") }
                        TextButton(enabled = !exporting && !selectedNodes.isNullOrEmpty(), onClick = {
                            exporting = true
                            scope.launch {
                                try {
                                    val outputs = vm.getSelectedOutputs()
                                    if (outputs.isEmpty()) toaster.show("所选记录没有可导出的成品")
                                    else {
                                        if (!ensureMediaGalleryPermission(context)) {
                                            toaster.show("请授予存储权限后再次保存", type = ToastType.Error)
                                            return@launch
                                        }
                                        var saved = 0
                                        outputs.forEach { output ->
                                            runCatching { saveMediaToGallery(context, vm.resolve(output.path), output.mimeType) }
                                                .onSuccess { saved++ }
                                                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                                        }
                                        toaster.show("已保存 $saved / ${outputs.size} 个文件")
                                    }
                                } finally { exporting = false }
                            }
                        }) { Text(if (exporting) "保存中…" else "保存所选") }
                        TextButton(enabled = !exporting && !selectedNodes.isNullOrEmpty(), onClick = {
                            scope.launch {
                                val outputs = vm.getSelectedOutputs()
                                if (outputs.isEmpty()) toaster.show("所选记录没有可分享的成品")
                                else runCatching { shareMediaBatch(context, outputs.map { vm.resolve(it.path) to it.mimeType }) }
                                    .onFailure { toaster.show("无法打开分享应用", type = ToastType.Error) }
                            }
                        }) { Text("分享所选") }
                        TextButton(enabled = !exporting && !selectedNodes.isNullOrEmpty(),
                            onClick = { confirmDeleteSelected = true }) { Text("删除所选") }
                    }
                    HorizontalDivider()
                }
            }
            if (selectedNodes == null) item("composer") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        OutlinedButton(onClick = { models = true }) {
                            Text(selection?.let { "${it.provider.name} · ${it.model.displayName.ifBlank { it.model.modelId }}" }
                                ?: "选择图片／视频模型")
                        }
                        TextButton(onClick = { parameters = true }, enabled = selection != null) { Text("参数") }
                    }
                    OutlinedTextField(value = vm.promptState.text.toString(),
                        onValueChange = { vm.promptState.setTextAndPlaceCursorAtEnd(it) },
                        label = { Text("描述想创作的内容") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
                    draft.editingNodeId?.let {
                        Row {
                            Text("正在修改原记录", Modifier.weight(1f))
                            TextButton(onClick = vm::stopEditing) { Text("改为新建") }
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(draft.assets, key = { it.path }) { asset ->
                            Card {
                                Column(Modifier.padding(8.dp).width(150.dp)) {
                                    MediaThumbnail(vm.resolve(asset.path), asset.type == MediaCreationAssetType.VIDEO,
                                        Modifier.fillMaxWidth().height(85.dp))
                                    if (asset.type == MediaCreationAssetType.IMAGE) {
                                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                                            selection?.capabilities?.imageRoles?.forEach { role ->
                                                TextButton(onClick = { vm.setAssetRole(asset, role) }) {
                                                    Text((if (asset.role == role) "✓ " else "") + role.label)
                                                }
                                            }
                                        }
                                    }
                                    Row {
                                        if (asset.type == MediaCreationAssetType.IMAGE) {
                                            TextButton(onClick = { sketching = MediaSketchRequest(asset.role, asset) }) { Text("涂画") }
                                        }
                                        TextButton(onClick = { vm.removeAsset(asset) }) { Text("移除") }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        if (selection?.capabilities?.imageRoles?.isNotEmpty() == true) {
                            TextButton(onClick = { images.launch("image/*") }) { Text("添加图片") }
                            TextButton(onClick = {
                                val roles = selection?.capabilities?.imageRoles.orEmpty()
                                sketching = MediaSketchRequest(if (ImageRole.REFERENCE in roles) ImageRole.REFERENCE else roles.firstOrNull() ?: ImageRole.REFERENCE)
                            }) { Text("画板") }
                        }
                        if (selection?.capabilities?.videoInput == true) {
                            TextButton(onClick = { videos.launch("video/*") }) { Text("添加视频") }
                        }
                        TextButton(onClick = vm::clearDraft) { Text("清空草稿") }
                        Button(onClick = vm::generate,
                            enabled = selection?.capabilities?.let { draft.canSubmit(it) } == true) { Text("生成") }
                    }
                    selection?.let {
                        if (draft.assets.mixesFramesWithReferences(it.capabilities)) {
                            Text("此接口不能同时使用首尾帧和参考素材，请移除其中一组。", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }
            items(nodes.itemCount, key = { index -> nodes.peek(index)?.id?.toString() ?: "placeholder-$index" }) { index ->
                val node = nodes[index]
                if (node != null) MediaCreationRecordCard(node, vm,
                    selecting = selectedNodes != null, selected = node.id in selectedNodes.orEmpty(),
                    onToggle = { vm.toggleSelection(node.id) }, onStartSelection = { vm.startSelection(node.id) },
                    onPreview = { preview = it },
                    onDelete = { deleting = it }, onSave = { output ->
                        scope.launch {
                            runCatching { saveMediaToGallery(context, vm.resolve(output.path), output.mimeType) }
                                .onSuccess { toaster.show("已保存") }
                                .onFailure { toaster.show(it.message ?: "保存失败", type = ToastType.Error) }
                        }
                    })
            }
        }
    }
    sketching?.let { request ->
        SketchDialog(
            onDismiss = { sketching = null },
            onConfirm = { result -> vm.addSketch(result, request.role, request.asset); sketching = null },
            image = request.asset?.let { Uri.fromFile(vm.resolve(it.path)) },
            aspectRatio = draft.params.sketchAspectRatio(),
        )
    }
    if (confirmDeleteSelected) ConfirmMediaDelete(
        "删除所选的 ${selectedNodes.orEmpty().size} 项及全部版本、素材和成品？已提交给服务商的任务无法撤回。",
        onDismiss = { confirmDeleteSelected = false },
        onConfirm = { vm.deleteSelected(); confirmDeleteSelected = false },
    )
    if (models) AlertDialog(onDismissRequest = { models = false }, title = { Text("选择模型") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            settings.mediaGenerationProviders.forEach { provider ->
                provider.models.filter { provider.capabilities(it.kind) != null }.forEach { model ->
                    TextButton(onClick = { vm.selectModel(provider, model); models = false }) {
                        Text("${provider.name} · ${model.displayName.ifBlank { model.modelId }} · ${if (model.kind == MediaKind.IMAGE) "图片" else "视频"}")
                    }
                }
            }
            TextButton(onClick = { models = false; nav.navigate(Screen.SettingMedia) }) { Text("配置媒体提供商") }
        }
    }, confirmButton = { TextButton(onClick = { models = false }) { Text("关闭") } })
    if (parameters) selection?.let { selected ->
        MediaCreationParametersDialog(draft.params, selected, onDismiss = { parameters = false }) {
            vm.updateParams(it); parameters = false
        }
    }
    if (renaming) RenameSessionDialog(session?.title.orEmpty(), onDismiss = { renaming = false }, onConfirm = {
        vm.rename(it); renaming = false
    })
    if (uploadRequired) AlertDialog(onDismissRequest = { uploadRequired = false }, title = { Text("需要上传素材") },
        text = { Text("此视频接口需要公网素材地址。配置独立的 S3 素材存储后，提交时会把选中的素材上传并提供临时签名地址。") },
        confirmButton = { TextButton(onClick = { uploadRequired = false; nav.navigate(Screen.SettingMedia) }) { Text("去配置") } },
        dismissButton = { TextButton(onClick = { uploadRequired = false }) { Text("取消") } })
    deleting?.let { record ->
        ConfirmMediaDelete("删除这个版本及其文件？", { deleting = null }, { vm.delete(record); deleting = null })
    }
    preview?.let { output ->
        val file = vm.resolve(output.path)
        if (output.mimeType.startsWith("video/")) VideoPlayerDialog(file) { preview = null }
        else ImagePreviewDialog(listOf(file.toURI().toString())) { preview = null }
    }
}

@Composable
private fun MediaCreationRecordCard(
    node: MediaCreationNode, vm: MediaCreationVM,
    selecting: Boolean, selected: Boolean, onToggle: () -> Unit, onStartSelection: () -> Unit,
    onPreview: (MediaCreationOutput) -> Unit,
    onDelete: (MediaCreationRecord) -> Unit, onSave: (MediaCreationOutput) -> Unit,
) {
    val record = node.record
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().combinedClickable(
        onClick = { if (selecting) onToggle() }, onLongClick = onStartSelection,
    )) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                if (selecting) Checkbox(checked = selected, onCheckedChange = { onToggle() })
                Text(record.prompt.ifBlank { "素材创作" }, style = MaterialTheme.typography.titleMedium)
            }
            Text("${record.providerName} · ${record.modelId} · ${record.status.label}")
            record.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (record.status.isActive) LinearProgressIndicator(Modifier.fillMaxWidth())
            record.outputs.forEach { output ->
                val video = output.mimeType.startsWith("video/")
                MediaThumbnail(vm.resolve(output.path), video, Modifier.fillMaxWidth().height(220.dp)
                    .clickable { if (selecting) onToggle() else onPreview(output) }, output.posterPath?.let(vm::resolve))
                if (!selecting) Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { onSave(output) }) { Text("保存") }
                    TextButton(onClick = { shareMedia(context, vm.resolve(output.path), output.mimeType) }) { Text("分享") }
                    if (video) {
                        TextButton(onClick = { vm.useVideo(output) }) { Text("用作参考视频") }
                        TextButton(onClick = { vm.continueVideo(output) }) { Text("尾帧续写") }
                    } else {
                        TextButton(onClick = { vm.useImage(output, ImageRole.REFERENCE) }) { Text("用作参考图") }
                        TextButton(onClick = { vm.useImage(output, ImageRole.FIRST_FRAME) }) { Text("用作首帧") }
                    }
                }
            }
            if (!selecting) Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (record.status.isActive) TextButton(onClick = { vm.cancel(record) }) { Text("停止等待") }
                else {
                    if (record.status != MediaCreationStatus.SUCCEEDED) TextButton(onClick = { vm.retry(record) }) { Text("重试／继续查询") }
                    TextButton(onClick = { vm.rerun(record) }) { Text("再生成一版") }
                    TextButton(onClick = { vm.editFrom(record) }) { Text("修改") }
                }
                TextButton(onClick = { onDelete(record) }) { Text("删除") }
            }
            if (!selecting && node.versionCount > 1) Row {
                TextButton(onClick = { vm.switchVersion(node, -1) }, enabled = node.versionIndex > 0) { Text("上一版") }
                Text("${node.versionIndex + 1}/${node.versionCount}", Modifier.padding(12.dp))
                TextButton(onClick = { vm.switchVersion(node, 1) }, enabled = node.versionIndex + 1 < node.versionCount) { Text("下一版") }
            }
        }
    }
}

@Composable
private fun ConfirmMediaDelete(message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("确认删除") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun mediaCreationHeroKey(id: String): String = "media-creation-session-$id"

private data class MediaSketchRequest(val role: ImageRole, val asset: MediaCreationAsset? = null)
