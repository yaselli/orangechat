package me.rerere.rikkahub.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.io.File
import kotlin.math.abs
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.ThemeMaterialSetting
import me.rerere.rikkahub.data.datastore.UiMaterialStyle
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class GlobalAppearanceDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun oneWallpaperFollowsSettingsNavigationAndSoftDarkIsExplicit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("settings-wallpaper-", ".png", context.cacheDir)
        val imageColor = Color(0xFF275070)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(imageColor.toArgb())
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        var soft by mutableStateOf(false)
        var path by mutableStateOf(file.absolutePath)
        var style by mutableStateOf(UiMaterialStyle.FOG_GLASS)
        var container by mutableStateOf(Color(0xFFF0F5FA))
        val stack = mutableStateListOf<NavKey>(Screen.Setting)
        try {
            compose.setContent {
                val material = ThemeMaterialSetting(style)
                val display =
                    DisplaySetting(settingsBackgroundPath = path, themeMaterial = material)
                val scheme =
                    if (soft) darkColorScheme().withSoftDarkAppearance()
                    else lightColorScheme(surfaceContainerLow = container)
                MaterialTheme(colorScheme = scheme.withThemeMaterial(material, soft)) {
                    CompositionLocalProvider(
                        LocalThemeMaterial provides material,
                        LocalDarkMode provides soft,
                        LocalSoftDarkMode provides soft,
                    ) {
                        NavDisplay(
                            backStack = stack,
                            entryDecorators = listOf(rememberSettingsBackdropDecorator(display)),
                            onBack = { stack.removeLastOrNull() },
                            entryProvider = entryProvider {
                                    entry<Screen.Setting> { Scene("设置主页") }
                                    entry<Screen.SettingDisplayTheme> { Scene("主题子页面") }
                                    entry<Screen.SettingProviderDetail> { Scene("提供商详情") }
                                }
                                    .withSettingsBackdropMetadata(),
                        )
                    }
                }
            }
            compose.waitUntil(5_000) { matchesBackdrop(imageColor) }
            val fog =
                compose.onNodeWithTag("global-card").captureToImage().toPixelMap().let {
                    it[it.width * 3 / 4, it.height * 3 / 4]
                }
            compose.runOnIdle { style = UiMaterialStyle.FROSTED }
            compose.mainClock.advanceTimeBy(250)
            val matte =
                compose.onNodeWithTag("global-card").captureToImage().toPixelMap().let {
                    it[it.width * 3 / 4, it.height * 3 / 4]
                }
            assertTrue(
                "Global cards did not use the selected material",
                abs(fog.red - matte.red) > 0.04f,
            )
            compose.runOnIdle {
                container = Color(0xFFE6EEF8)
                style = UiMaterialStyle.FOG_GLASS
            }
            compose.mainClock.advanceTimeBy(250)
            val recolored = compose.onNodeWithTag("global-card").captureToImage().toPixelMap()
            assertTrue(
                "Palette change made the global card opaque",
                recolored[recolored.width * 3 / 4, recolored.height * 3 / 4].red < 0.75f,
            )
            for (dark in listOf(false, true)) {
                compose.runOnIdle { soft = dark }
                val expected =
                    if (dark) Color.Black.copy(alpha = 0.28f).compositeOver(imageColor)
                    else imageColor
                compose.waitUntil(5_000) { matchesBackdrop(expected) }
                compose.runOnIdle { stack.add(Screen.SettingDisplayTheme) }
                compose.onNodeWithText("主题子页面").assertIsDisplayed()
                compose.waitUntil(5_000) { matchesBackdrop(expected) }
                compose.runOnIdle { stack.add(Screen.SettingProviderDetail("fixture")) }
                compose.onNodeWithText("提供商详情").assertIsDisplayed()
                compose.waitUntil(5_000) { matchesBackdrop(expected) }
                compose.runOnIdle {
                    stack.removeLastOrNull()
                    stack.removeLastOrNull()
                }
                compose.onNodeWithText("设置主页").assertIsDisplayed()
                compose.waitUntil(5_000) { matchesBackdrop(expected) }
            }
            compose.runOnIdle { path = "" }
            compose.waitUntil(5_000) {
                !matchesBackdrop(Color.Black.copy(alpha = 0.28f).compositeOver(imageColor))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun globalMaterialControlsKeepClickActionsAndPopupsDoNotSampleAnotherWindow() {
        var menu by mutableStateOf(false)
        var dialog by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalThemeMaterial provides ThemeMaterialSetting()) {
                    MaterialBackdrop(background = { ThemeMaterialBackground() }) {
                        MaterialCard(
                            onClick = { menu = true },
                            modifier = Modifier.testTag("open-menu"),
                        ) {
                            Text("打开菜单")
                        }
                        MaterialDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Text("菜单来源为空=${LocalMaterialBackdrop.current == null}")
                        }
                        if (dialog)
                            MaterialAlertDialog(
                                onDismissRequest = { dialog = false },
                                confirmButton = { Text("确定") },
                                text = { Text("弹窗来源为空=${LocalMaterialBackdrop.current == null}") },
                            )
                    }
                }
            }
        }
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithText("菜单来源为空=true").assertIsDisplayed()
        compose.runOnIdle {
            menu = false
            dialog = true
        }
        compose.onNodeWithText("弹窗来源为空=true").assertIsDisplayed()
    }

    @Composable
    private fun Scene(title: String) {
        Scaffold { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                MaterialCard(
                    modifier = Modifier.padding(24.dp).size(160.dp, 72.dp).testTag("global-card")
                ) {
                    Text(title, Modifier.padding(8.dp))
                }
            }
        }
    }

    private fun matchesBackdrop(expected: Color): Boolean {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val actual = pixels[pixels.width * 9 / 10, pixels.height * 8 / 10]
        return abs(expected.red - actual.red) < 0.015f &&
            abs(expected.green - actual.green) < 0.015f &&
            abs(expected.blue - actual.blue) < 0.015f
    }
}
