/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.translator

import androidx.compose.ui.platform.LocalConfiguration
import android.content.ClipData
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.LanguageCircle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import me.rerere.rikkahub.ui.theme.MaterialModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.getText
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

@Composable
fun TranslatorPage(vm: TranslatorVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val inputText by vm.inputText.collectAsStateWithLifecycle()
    val translatedText by vm.translatedText.collectAsStateWithLifecycle()
    val targetLanguage by vm.targetLanguage.collectAsStateWithLifecycle()
    val translating by vm.translating.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var showHistory by remember { mutableStateOf(false) }

    // 处理错误
    LaunchedEffect(Unit) {
        vm.errorFlow.collect { error ->
            toaster.show(error.message ?: "错误", type = ToastType.Error)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.translator_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    TextButton(onClick = { showHistory = true }) { Text(stringResource(R.string.translator_page_history)) }
                    ModelSelector(
                        modelId = settings.translateModeId,
                        onSelect = {
                            vm.updateSettings(settings.copy(translateModeId = it.id))
                        },
                        providers = settings.providers,
                        type = ModelType.CHAT,
                        onlyIcon = true,
                    )
                }
            )
        },
        bottomBar = {
            BottomBar(
                translating = translating,
                onTranslate = {
                    vm.translate()
                },
                onCancelTranslation = {
                    vm.cancelTranslation()
                },
                onLanguageSelected = {
                    vm.updateTargetLanguage(it)
                },
                targetLanguage = targetLanguage
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 输入区域
            Column {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { vm.updateInputText(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.translator_page_input_placeholder)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent
                    ),
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.headlineSmall,
                )

                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            clipboard.getClipEntry()?.clipData?.getText()?.let {
                                vm.updateInputText(it)
                            }
                        }
                    }
                ) {
                    Icon(HugeIcons.Clipboard, null)
                    Text("粘贴文本", modifier = Modifier.padding(start = 4.dp))
                }
            }

            // 翻译进度条
            Crossfade(translating) { isTranslating ->
                if (isTranslating) {
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                    )
                } else {
                    HorizontalDivider()
                }
            }

            // 翻译结果
            SelectionContainer {
                Text(
                    text = translatedText.ifEmpty {
                        stringResource(R.string.translator_page_result_placeholder)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )
            }

            AnimatedVisibility(translatedText.isNotBlank()) {
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(
                                    ClipData.newPlainText(
                                        null, translatedText
                                    )
                                )
                            )
                        }
                    }
                ) {
                    Icon(HugeIcons.Clipboard, null)
                    Text("复制翻译结果", modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (translatedText.isNotBlank()) {
                TextButton(enabled = !translating, onClick = vm::useResultAsInput) { Text("将结果用作输入") }
            }
        }
    }
    if (showHistory) {
        val history = vm.history.collectAsLazyPagingItems()
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                TranslationHistoryHeader(onClear = vm::clearHistory)
                Text("自动保存最近 100 条成功翻译；点击可恢复，左滑可删除。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                when {
                    history.loadState.refresh is LoadState.Loading -> CircularProgressIndicator(Modifier.padding(16.dp))
                    history.loadState.refresh is LoadState.Error -> TextButton(onClick = history::retry) { Text("加载失败，点击重试") }
                    history.itemCount == 0 -> Text("还没有翻译历史", Modifier.padding(16.dp))
                }
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 500.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(count = history.itemCount, key = history.itemKey { it.id }) { index ->
                        history[index]?.let { entry ->
                            TranslationHistoryItem(entry,
                                onClick = { vm.restoreHistory(entry); showHistory = false },
                                onDelete = { vm.deleteHistory(entry) })
                        }
                    }
                    if (history.loadState.append is LoadState.Error) {
                        item { TextButton(onClick = history::retry) { Text("加载更多失败，点击重试") } }
                    }
                }
            }
        }
    }

}

private val Locales by lazy {
    listOf(
        Locale.SIMPLIFIED_CHINESE,
        Locale.ENGLISH,
        Locale.TRADITIONAL_CHINESE,
        Locale.JAPANESE,
        Locale.KOREAN,
        Locale.FRENCH,
        Locale.GERMAN,
        Locale.ITALIAN,
        Locale("es", "ES")
    )
}

@Composable
private fun LanguageSelector(
    targetLanguage: Locale,
    onLanguageSelected: (Locale) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    @Composable
    fun getLanguageDisplayName(locale: Locale): String {
        return when (locale) {
            Locale.SIMPLIFIED_CHINESE -> stringResource(R.string.language_simplified_chinese)
            Locale.ENGLISH -> stringResource(R.string.language_english)
            Locale.TRADITIONAL_CHINESE -> stringResource(R.string.language_traditional_chinese)
            Locale.JAPANESE -> stringResource(R.string.language_japanese)
            Locale.KOREAN -> stringResource(R.string.language_korean)
            Locale.FRENCH -> stringResource(R.string.language_french)
            Locale.GERMAN -> stringResource(R.string.language_german)
            Locale.ITALIAN -> stringResource(R.string.language_italian)
            Locale("es", "ES") -> stringResource(R.string.language_spanish)
            else -> locale.getDisplayLanguage(LocalConfiguration.current.locales[0])
        }
    }

    Box(
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = getLanguageDisplayName(targetLanguage),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent
                )
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                Locales.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(getLanguageDisplayName(language)) },
                        onClick = {
                            onLanguageSelected(language)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    targetLanguage: Locale,
    onLanguageSelected: (Locale) -> Unit,
    translating: Boolean,
    onTranslate: () -> Unit,
    onCancelTranslation: () -> Unit
) {
    BottomAppBar(
        actions = {
            // 目标语言选择
            LanguageSelector(
                targetLanguage = targetLanguage,
                onLanguageSelected = { onLanguageSelected(it) }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (translating) {
                        onCancelTranslation()
                    } else {
                        onTranslate()
                    }
                },
                containerColor = BottomAppBarDefaults.bottomAppBarFabColor,
                elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation()
            ) {
                if (!translating) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            HugeIcons.LanguageCircle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            stringResource(R.string.translator_page_translate),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                } else {
                    Text(stringResource(R.string.translator_page_cancel))
                }
            }
        }
    )
}
