/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpManager

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager
) :
    ViewModel() {
    private val mutableSettings = MutableStateFlow(
        Settings(init = true, providers = emptyList())
    )
    val settings: StateFlow<Settings> = mutableSettings.asStateFlow()

    private var pendingWrites = 0

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collectLatest { committed ->
                // Do not let an older disk emission visually undo a newer tap.
                if (pendingWrites == 0) mutableSettings.value = committed
            }
        }
    }

    fun updateSettings(
        settings: Settings,
        previous: Settings = this.settings.value,
        onCommitted: ((Settings) -> Unit)? = null,
    ) {
        // Compose sees the new switch value before encryption or disk I/O starts.
        mutableSettings.value = settings
        pendingWrites++
        viewModelScope.launch {
            try {
                persistThenNotify(
                    persist = { settingsStore.updateFrom(previous, settings) },
                    readCommitted = { settingsStore.settingsFlowRaw.first() },
                    onCommitted = onCommitted,
                )
            } finally {
                pendingWrites--
                if (pendingWrites == 0) {
                    mutableSettings.value = settingsStore.settingsFlowRaw.first()
                }
            }
        }
    }

    fun addCustomTheme(theme: me.rerere.rikkahub.ui.theme.CustomTheme) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(customThemes = settings.customThemes + theme)
            }
        }
    }

    fun updateCustomTheme(theme: me.rerere.rikkahub.ui.theme.CustomTheme) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    customThemes = settings.customThemes.map {
                        if (it.id == theme.id) theme else it
                    }
                )
            }
        }
    }

    fun deleteCustomTheme(themeId: String) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                val ns = settings.copy(customThemes = settings.customThemes.filter { it.id != themeId })
                if (settings.themeId == themeId) ns.copy(themeId = me.rerere.rikkahub.ui.theme.PresetThemes[0].id) else ns
            }
        }
    }
}
