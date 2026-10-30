package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import me.rerere.rikkahub.ui.theme.MaterialAlertDialog as AlertDialog
import me.rerere.rikkahub.ui.theme.MaterialCard as Card
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.remote.RemoteFileStore
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingMediaPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val store: SettingsStore = koinInject()
    val remote: RemoteFileStore = koinInject()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }
    var deleting by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }
    var upload by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // Updating only this collection under the store lock avoids overwriting token refreshes or unrelated settings.
    fun saveProvider(provider: MediaGenerationProviderSetting) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                store.update { current -> current.copy(mediaGenerationProviders =
                    current.mediaGenerationProviders.filterNot { it.id == provider.id } + provider) }
                editing = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toaster.show("保存失败，请重试", type = ToastType.Error) }
            finally { saving = false }
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("媒体提供商") }, navigationIcon = { BackButton() }, actions = {
            TextButton(onClick = { adding = true }, enabled = !settings.init) { Text("添加") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("图片和视频使用独立提供商配置。旧生图工具仍使用原来的聊天模型配置。")
            }
            items(settings.mediaGenerationProviders, key = { it.id.toString() }) { provider ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(provider.name, style = MaterialTheme.typography.titleMedium)
                        Text("${provider.models.size} 个模型 · ${mediaProviderTypeName(provider)}")
                        Row {
                            TextButton(onClick = { editing = provider }) { Text("编辑") }
                            TextButton(onClick = { deleting = provider }) { Text("删除") }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("素材上传", style = MaterialTheme.typography.titleMedium)
                        Text("仅在接口要求公网素材且你提交素材时上传。使用独立 S3 配置，不使用备份设置；签名地址默认有效 24 小时，上传缓存保留 7 天。")
                        Text(if (remote.isConfigured) "已配置" else "未配置；文生视频与本地图片编辑不受影响")
                        Row {
                            TextButton(onClick = { upload = true }) { Text("配置存储") }
                            TextButton(enabled = !testing && remote.isConfigured, onClick = {
                                testing = true
                                scope.launch {
                                    try {
                                        remote.testConnection().fold(
                                            onSuccess = { toaster.show("连接成功") },
                                            onFailure = { toaster.show("连接失败，请检查存储配置", type = ToastType.Error) })
                                    } finally { testing = false }
                                }
                            }) { Text(if (testing) "测试中…" else "测试连接") }
                        }
                    }
                }
            }
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("选择服务商") }, text = {
        Column {
            listOf("OpenAI", "阿里云百炼", "火山方舟", "MiniMax", "OpenRouter").forEachIndexed { index, name ->
                TextButton(onClick = {
                    editing = when (index) {
                        0 -> MediaGenerationProviderSetting.OpenAI()
                        1 -> MediaGenerationProviderSetting.Aliyun()
                        2 -> MediaGenerationProviderSetting.Volcengine()
                        3 -> MediaGenerationProviderSetting.MiniMax()
                        else -> MediaGenerationProviderSetting.OpenRouter()
                    }
                    adding = false
                }) { Text(name) }
            }
        }
    }, confirmButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
    editing?.let { provider ->
        key(provider.id) { MediaProviderEditor(provider, saving, { if (!saving) editing = null }, ::saveProvider) }
    }
    deleting?.let { provider ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除 ${provider.name}？") },
            text = { Text("历史记录和已下载文件保留。该提供商未完成的任务将无法继续查询。") },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    store.update { it.copy(mediaGenerationProviders = it.mediaGenerationProviders.filterNot { p -> p.id == provider.id }) }
                }
                deleting = null
            }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    if (upload) MediaUploadEditor(settings.uploadS3Config, { upload = false }) { config ->
        scope.launch {
            try {
                store.update { it.copy(uploadS3Config = config) }
                upload = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toaster.show("保存失败，请重试", type = ToastType.Error) }
        }
    }
}

private fun mediaProviderTypeName(provider: MediaGenerationProviderSetting): String = when (provider) {
    is MediaGenerationProviderSetting.OpenAI -> "OpenAI 兼容"
    is MediaGenerationProviderSetting.Aliyun -> "阿里云百炼"
    is MediaGenerationProviderSetting.Volcengine -> "火山方舟"
    is MediaGenerationProviderSetting.MiniMax -> "MiniMax"
    is MediaGenerationProviderSetting.OpenRouter -> "OpenRouter"
}

@Composable
private fun MediaProviderEditor(
    original: MediaGenerationProviderSetting, saving: Boolean,
    onDismiss: () -> Unit, onSave: (MediaGenerationProviderSetting) -> Unit,
) {
    // Credentials deliberately do not enter rememberSaveable or saved-state bundles.
    var name by remember { mutableStateOf(original.name) }
    var url by remember { mutableStateOf(original.baseUrl) }
    var apiKey by remember { mutableStateOf(original.apiKey) }
    var workspace by remember { mutableStateOf((original as? MediaGenerationProviderSetting.Aliyun)?.workspaceId.orEmpty()) }
    var models by remember { mutableStateOf(original.models) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(mediaProviderTypeName(original)) }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            OutlinedTextField(url, { url = it }, label = { Text("接口地址") }, singleLine = true)
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            if (original is MediaGenerationProviderSetting.Aliyun) {
                OutlinedTextField(workspace, { workspace = it }, label = { Text("业务空间 ID") }, singleLine = true)
            }
            Text("模型", style = MaterialTheme.typography.titleSmall)
            models.forEach { model ->
                key(model.id) {
                    Column {
                        OutlinedTextField(model.modelId, { value -> models = models.map { if (it.id == model.id) it.copy(modelId = value) else it } },
                            label = { Text(if (model.kind == MediaKind.IMAGE) "图片模型 ID" else "视频模型 ID") }, singleLine = true)
                        OutlinedTextField(model.displayName, { value -> models = models.map { if (it.id == model.id) it.copy(displayName = value) else it } },
                            label = { Text("显示名称（可选）") }, singleLine = true)
                        TextButton(onClick = { models = models.filterNot { it.id == model.id } }) { Text("移除模型") }
                    }
                }
            }
            Row {
                original.supportedKinds.forEach { kind ->
                    TextButton(onClick = { models = models + MediaGenerationModel("", kind) }) {
                        Text(if (kind == MediaKind.IMAGE) "添加图片模型" else "添加视频模型")
                    }
                }
            }
        }
    }, confirmButton = {
        TextButton(enabled = !saving && name.isNotBlank() && apiKey.isNotBlank() && url.startsWith("http") &&
            models.all { it.modelId.isNotBlank() }, onClick = {
            val edited = original.copyProvider(name = name.trim(), apiKey = apiKey.trim(), baseUrl = url.trim().trimEnd('/'),
                models = models.map { it.copy(modelId = it.modelId.trim(), displayName = it.displayName.trim()) })
            onSave(if (edited is MediaGenerationProviderSetting.Aliyun) edited.copy(workspaceId = workspace.trim()) else edited)
        }) { Text(if (saving) "保存中…" else "保存") }
    }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun MediaUploadEditor(original: S3Config, onDismiss: () -> Unit, onSave: (S3Config) -> Unit) {
    var config by remember { mutableStateOf(original) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("素材 S3 存储") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(config.endpoint, { config = config.copy(endpoint = it) }, label = { Text("Endpoint") }, singleLine = true)
            OutlinedTextField(config.bucket, { config = config.copy(bucket = it) }, label = { Text("Bucket") }, singleLine = true)
            OutlinedTextField(config.region, { config = config.copy(region = it) }, label = { Text("Region") }, singleLine = true)
            OutlinedTextField(config.accessKeyId, { config = config.copy(accessKeyId = it) }, label = { Text("Access Key ID") },
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(config.secretAccessKey, { config = config.copy(secretAccessKey = it) }, label = { Text("Secret Access Key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            Row {
                Text("路径风格", Modifier.weight(1f))
                Switch(config.pathStyle, { config = config.copy(pathStyle = it) })
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(config.copy(endpoint = config.endpoint.trim(), bucket = config.bucket.trim(),
        region = config.region.trim(), accessKeyId = config.accessKeyId.trim(), secretAccessKey = config.secretAccessKey.trim()).withHttpsByDefault()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
