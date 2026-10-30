/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LargeFlexibleTopAppBar
import me.rerere.rikkahub.ui.theme.MaterialListItem as ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.UiMaterialStyle
import me.rerere.rikkahub.data.datastore.VisualThemePalette
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.pages.setting.components.PresetThemeButtonGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import androidx.compose.material3.Slider

@Composable
fun SettingDisplayThemePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var amoledDarkMode by rememberAmoledDarkMode()
    val navController = LocalNavController.current

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("主题外观") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_theme_setting),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                    )
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(
                                RoundedCornerShape(
                                    topStart = 20.dp,
                                    topEnd = 20.dp,
                                    bottomStart = 4.dp,
                                    bottomEnd = 4.dp
                                )
                            ),
                        headlineContent = { Text(stringResource(R.string.setting_page_dynamic_color)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_dynamic_color_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.dynamicColor,
                                onCheckedChange = { vm.updateSettings(settings.copy(dynamicColor = it)) },
                            )
                        },
                        colors = CustomColors.listItemColors,
                    )
                    // Custom theme management entry
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { navController.navigate(Screen.SettingTheme) },
                        headlineContent = { Text("自定义主题管理") },
                        supportingContent = { Text("HCT 色彩算法自定义主题") },
                        colors = CustomColors.listItemColors,
                    )
                    if (!settings.dynamicColor) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceBright)
                        ) {
                            PresetThemeButtonGroup(
                                themeId = settings.themeId,
                                modifier = Modifier.fillMaxWidth(),
                                onChangeTheme = { vm.updateSettings(settings.copy(themeId = it)) }
                            )
                        }
                    }
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(
                                RoundedCornerShape(
                                    topStart = 4.dp,
                                    topEnd = 4.dp,
                                    bottomStart = 20.dp,
                                    bottomEnd = 20.dp
                                )
                            ),
                        headlineContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_desc)) },
                        trailingContent = {
                            Switch(
                                checked = amoledDarkMode,
                                onCheckedChange = { amoledDarkMode = it }
                            )
                        },
                        colors = CustomColors.listItemColors,
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("主题配色（单选）") },
                ) {
                    listOf(
                        VisualThemePalette.ORIGINAL,
                        VisualThemePalette.SEA_SALT,
                        VisualThemePalette.APRICOT_CREAM,
                        VisualThemePalette.MIST_BLUE,
                    ).forEach { palette ->
                        item(
                            headlineContent = {
                                Text(palette.paletteTitle())
                            },
                            supportingContent = {
                                Text(
                                    palette.paletteDescription()
                                )
                            },
                            trailingContent = {
                                RadioButton(
                                    selected = displaySetting.visualThemePalette == palette,
                                    onClick = {
                                        updateDisplaySetting(displaySetting.copy(visualThemePalette = palette))
                                    },
                                )
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("整体主题材质（单选）") },
                ) {
                    UiMaterialStyle.entries.forEach { style ->
                        item(
                            headlineContent = { Text(style.materialTitle()) },
                            supportingContent = { Text(style.materialDescription()) },
                            trailingContent = {
                                RadioButton(
                                    selected = displaySetting.themeMaterial.style == style,
                                    onClick = {
                                        updateDisplaySetting(displaySetting.copy(
                                                themeMaterial =
                                                    displaySetting.themeMaterial.copy(style = style)
                                            ))
                                    },
                                )
                            },
                        )
                    }
                }
            }

            if (displaySetting.themeMaterial.style == UiMaterialStyle.FOG_GLASS) {
                item {
                    var transparency by
                        remember(displaySetting.themeMaterial.safeFogTransparency) {
                            mutableStateOf(displaySetting.themeMaterial.safeFogTransparency)
                        }
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text("毛玻璃透明度：${(transparency * 100).toInt()}%")
                        Text("数值越大越通透，文字保持清晰", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = transparency,
                            onValueChange = { transparency = it },
                            onValueChangeFinished = {
                                updateDisplaySetting(
                                    displaySetting.copy(
                                        themeMaterial =
                                            displaySetting.themeMaterial.copy(
                                                fogTransparency = transparency
                                            )
                                    )
                                )
                            },
                            valueRange = 0f..1f,
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("侧边栏动效") },
                ) {
                    item(
                        headlineContent = { Text("立体侧滑动效") },
                        supportingContent = { Text("侧边栏打开时，聊天页右移并产生立体透视") },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableDrawerCardTransform,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableDrawerCardTransform = it))
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun UiMaterialStyle.materialTitle(): String = when (this) {
    UiMaterialStyle.ORIGINAL -> "原始"
    UiMaterialStyle.FOG_GLASS -> "毛玻璃"
        UiMaterialStyle.LIQUID_GLASS -> "水玻璃"
    UiMaterialStyle.FROSTED -> "磨砂"
}

private fun UiMaterialStyle.materialDescription(): String = when (this) {
    UiMaterialStyle.ORIGINAL -> "保留应用原有材质"
    UiMaterialStyle.FOG_GLASS -> "柔雾轻透，背景化成淡淡的颜色"
        UiMaterialStyle.LIQUID_GLASS -> "清透润亮，圆润亮边与柔和反光"
        UiMaterialStyle.FROSTED -> "柔润哑光，轻柔底色与细腻雾面"
    }

private fun VisualThemePalette.paletteTitle(): String = when (this) {
    VisualThemePalette.ORIGINAL -> "原始配色"
    VisualThemePalette.SEA_SALT -> "海盐"
    VisualThemePalette.APRICOT_CREAM -> "奶杏"
    VisualThemePalette.MIST_BLUE -> "雾蓝"
    VisualThemePalette.SAKURA_MIST -> "樱雾"
    VisualThemePalette.AURORA -> "极光"
    VisualThemePalette.MIDNIGHT -> "午夜"
}

private fun VisualThemePalette.paletteDescription(): String = when (this) {
    VisualThemePalette.ORIGINAL -> "使用应用原有主题配色"
    VisualThemePalette.SEA_SALT -> "清透、低饱和的海盐配色"
    VisualThemePalette.APRICOT_CREAM -> "奶油暖白与柔和杏色，支持浅色与深色"
    VisualThemePalette.MIST_BLUE -> "清淡雾蓝与冰白，支持浅色与深色"
    else -> "跟随当前配色设置"
}
