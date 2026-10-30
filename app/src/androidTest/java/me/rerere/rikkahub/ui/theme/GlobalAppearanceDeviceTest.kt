package me.rerere.rikkahub.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.ThemeMaterialSetting
import me.rerere.rikkahub.data.datastore.UiMaterialStyle
import me.rerere.rikkahub.ui.components.message.ChatMessageActionsSheet
import org.junit.Assert.assertEquals
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
                            entryProvider =
                                entryProvider<NavKey> {
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

    @Test
    fun sheetBackgroundMovesWithTheSheetAndNeverWashesOverTheChat() {
        val wallpaper = Color(0xFF274B78)
        val scrim = Color.Black.copy(alpha = 0.32f)
        var style by mutableStateOf(UiMaterialStyle.FOG_GLASS)
        var dark by mutableStateOf(false)
        var open by mutableStateOf(true)
        compose.setContent {
            val material = ThemeMaterialSetting(style)
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme.withThemeMaterial(material, dark)) {
                CompositionLocalProvider(
                    LocalThemeMaterial provides material,
                    LocalDarkMode provides dark,
                    LocalMaterialSurfaceOpacity provides 0.25f,
                ) {
                    Box(Modifier.fillMaxSize().background(wallpaper))
                    if (open)
                        MaterialModalBottomSheet(
                            onDismissRequest = { open = false },
                            sheetState =
                                rememberModalBottomSheetState(skipPartiallyExpanded = true),
                            scrimColor = scrim,
                            containerColor = scheme.surfaceContainerLow,
                            dragHandle = null,
                        ) {
                            Box(Modifier.fillMaxWidth().height(160.dp).testTag("sheet-body")) {
                                Text("面板来源为空=${LocalMaterialBackdrop.current == null}")
                            }
                        }
                }
            }
        }
        for (mode in listOf(false, true)) {
            for (material in UiMaterialStyle.entries) {
                compose.runOnIdle {
                    style = material
                    dark = mode
                }
                compose.onNodeWithText("面板来源为空=true").assertIsDisplayed()
                compose.waitForIdle()
                val body = compose.onNodeWithTag("sheet-body").fetchSemanticsNode().boundsInWindow
                val screen =
                    requireNotNull(
                        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    )
                try {
                    assertTrue(
                        "The short sheet should leave space above it",
                        body.top > screen.height / 3f,
                    )
                    val outside = Color(screen.getPixel(screen.width / 2, (body.top / 2).toInt()))
                    assertColorNear(
                        "Sheet material escaped into the chat",
                        scrim.compositeOver(wallpaper),
                        outside,
                    )
                    val inside =
                        Color(
                            screen.getPixel(
                                body.center.x.toInt(),
                                (body.top + body.height * 0.75f).toInt(),
                            )
                        )
                    val scheme = if (mode) darkColorScheme() else lightColorScheme()
                    assertColorNear(
                        "Chat shows through the sheet container",
                        scheme.surfaceContainerLow,
                        inside,
                    )
                } finally {
                    screen.recycle()
                }
            }
        }
        compose.runOnIdle { open = false }
        compose.onNodeWithTag("sheet-body").assertDoesNotExist()
    }

    @Test
    fun longMessageMenuScrollsToDeleteAtLargeFontSizeAndHasNoReplyQuoteEntry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var open by mutableStateOf(true)
        var deleted = false
        compose.setContent {
            val density = LocalDensity.current
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, 1.8f),
                    LocalThemeMaterial provides ThemeMaterialSetting(),
                ) {
                    if (open)
                        ChatMessageActionsSheet(
                            message = UIMessage.user("测试消息"),
                            model = null,
                            onDelete = { deleted = true },
                            onEdit = {},
                            onShare = {},
                            onFork = {},
                            onSelectAndCopy = {},
                            onReact = {},
                            onToggleFavorite = {},
                            onShowProcess = {},
                            onWebViewPreview = {},
                            onDismissRequest = { open = false },
                        )
                }
            }
        }
        compose.onNodeWithText("引用").assertDoesNotExist()
        compose
            .onNodeWithText(context.getString(R.string.delete))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            assertTrue(deleted)
            assertEquals(false, open)
        }
    }

    private fun assertColorNear(message: String, expected: Color, actual: Color) {
        assertTrue(
            "$message: expected=$expected actual=$actual",
            abs(expected.red - actual.red) < 0.035f &&
                abs(expected.green - actual.green) < 0.035f &&
                abs(expected.blue - actual.blue) < 0.035f,
        )
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
