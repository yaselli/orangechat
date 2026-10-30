/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkahub.ui.theme.MaterialModalBottomSheet as ModalBottomSheet
import me.rerere.rikkahub.ui.theme.MaterialSurface as Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.plugin.loader.PluginLoader
import org.koin.compose.koinInject

private const val STICKER_PLUGIN_ID = "com.orangechat.plugin.sticker"

private data class StickerItem(
    val id: String,
    val name: String,
    val url: String,
    val category: String,
)

@Composable
internal fun StickerPickerSheet(
    onDismiss: () -> Unit,
    onStickerSelected: (markdown: String) -> Unit,
) {
    val pluginLoader: PluginLoader = koinInject()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var stickers by remember { mutableStateOf<List<StickerItem>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryKey) {
        loading = true
        errorMessage = null

        pluginLoader.callTool(
            pluginId = STICKER_PLUGIN_ID,
            toolName = "list_stickers",
            params = buildJsonObject {},
        ).fold(
            onSuccess = { result ->
                val root = result as? JsonObject
                val error = root?.get("error")?.jsonPrimitive?.contentOrNull
                val data = root?.get("data") as? JsonObject

                if (data == null) {
                    errorMessage = error ?: "没有读取到表情包，请检查插件是否已启用并完成配置"
                } else {
                    stickers = data.flatMap { (category, value) ->
                        value.jsonArray.mapNotNull { element ->
                            val item = element.jsonObject
                            val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            val url = item["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            StickerItem(
                                id = item["id"]?.jsonPrimitive?.contentOrNull ?: "$category-$name-$url",
                                name = name,
                                url = url,
                                category = category,
                            )
                        }
                    }
                }
                loading = false
            },
            onFailure = { error ->
                errorMessage = error.message ?: "表情包插件调用失败"
                loading = false
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 620.dp)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "选择表情包",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                errorMessage != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { retryKey++ }) {
                            Text("重新加载")
                        }
                    }
                }

                stickers.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("表情包库还是空的，请先在插件页面上传表情包")
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = stickers,
                            key = { "${it.category}-${it.id}" },
                        ) { sticker ->
                            Surface(
                                onClick = {
                                    val safeName = sticker.name.replace("]", "")
                                    onStickerSelected("![$safeName](${sticker.url})")
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(8.dp),
                                ) {
                                    AsyncImage(
                                        model = sticker.url,
                                        contentDescription = sticker.name,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Fit,
                                    )
                                    Text(
                                        text = sticker.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
