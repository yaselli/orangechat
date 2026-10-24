package me.rerere.rikkahub.ui.pages.mediacreation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.mediagen.provider.MediaGenerationParameter
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.supportedBy

@Composable
internal fun MediaCreationParametersDialog(
    initial: MediaCreationParams,
    selected: MediaCreationModelSelection,
    onDismiss: () -> Unit,
    onSave: (MediaCreationParams) -> Unit,
) {
    var count by remember { mutableStateOf(initial.count?.toString().orEmpty()) }
    var resolution by remember { mutableStateOf(initial.resolution.orEmpty()) }
    var ratio by remember { mutableStateOf(initial.aspectRatio.orEmpty()) }
    var duration by remember { mutableStateOf(initial.durationSeconds?.toString().orEmpty()) }
    var seed by remember { mutableStateOf(initial.seed?.toString().orEmpty()) }
    var audio by remember { mutableStateOf(initial.generateAudio) }
    var watermark by remember { mutableStateOf(initial.watermark) }
    var enhance by remember { mutableStateOf(initial.promptEnhancement) }
    val capabilities = selected.capabilities
    val presets = selected.provider.presets(selected.model.kind)
    val badCount = count.isNotBlank() && (count.toIntOrNull()?.let { it > 0 } != true)
    val badDuration = duration.isNotBlank() && (duration.toIntOrNull()?.let { it == -1 || it > 0 } != true)
    val badSeed = seed.isNotBlank() && seed.toLongOrNull() == null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("创作参数") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("留空使用接口默认值；必填项会使用快捷选项中的首个值。具体模型接受的值以服务商为准。")
            if (MediaGenerationParameter.COUNT in capabilities.parameters) {
                OutlinedTextField(count, { count = it }, label = { Text("数量") }, isError = badCount, singleLine = true)
            }
            if (MediaGenerationParameter.RESOLUTION in capabilities.parameters) {
                OutlinedTextField(resolution, { resolution = it }, label = { Text("分辨率／尺寸") }, singleLine = true,
                    supportingText = { Text(presets.resolutions.joinToString("、")) })
            }
            if (MediaGenerationParameter.ASPECT_RATIO in capabilities.parameters) {
                OutlinedTextField(ratio, { ratio = it }, label = { Text("宽高比") }, singleLine = true,
                    supportingText = { Text(presets.aspectRatios.joinToString("、")) })
            }
            if (MediaGenerationParameter.DURATION in capabilities.parameters) {
                OutlinedTextField(duration, { duration = it }, label = { Text("时长（秒）") }, isError = badDuration,
                    singleLine = true, supportingText = { Text(presets.durations.joinToString("、")) })
            }
            if (MediaGenerationParameter.SEED in capabilities.parameters) {
                OutlinedTextField(seed, { seed = it }, label = { Text("随机种子") }, isError = badSeed, singleLine = true)
            }
            if (MediaGenerationParameter.GENERATE_AUDIO in capabilities.parameters) {
                MediaBooleanChoice("生成音频", audio) { audio = it }
            }
            if (MediaGenerationParameter.WATERMARK in capabilities.parameters) {
                MediaBooleanChoice("水印", watermark) { watermark = it }
            }
            if (MediaGenerationParameter.PROMPT_ENHANCEMENT in capabilities.parameters) {
                MediaBooleanChoice("提示词增强", enhance) { enhance = it }
            }
        }
    }, confirmButton = {
        TextButton(enabled = !badCount && !badDuration && !badSeed, onClick = {
            onSave(MediaCreationParams(count.toIntOrNull(), resolution.trim().ifBlank { null },
                ratio.trim().ifBlank { null }, duration.toIntOrNull(), audio, watermark, seed.toLongOrNull(), enhance)
                .supportedBy(capabilities))
        }) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun MediaBooleanChoice(label: String, value: Boolean?, onChange: (Boolean?) -> Unit) {
    Column {
        Text(label)
        Row {
            listOf(null to "默认", true to "开启", false to "关闭").forEach { (choice, name) ->
                FilterChip(selected = value == choice, onClick = { onChange(choice) }, label = { Text(name) },
                    modifier = Modifier.padding(end = 6.dp))
            }
        }
    }
}
