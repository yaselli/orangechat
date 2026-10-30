package me.rerere.rikkahub.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import coil3.compose.AsyncImagePainter
import java.io.File
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.ThemeMaterialSetting
import me.rerere.rikkahub.data.datastore.UiMaterialStyle
import me.rerere.rikkahub.ui.components.message.BubbleSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 32)
class RenderingFlickerDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun streamingBubbleKeepsItsTextAndGlassDuringResizeAndWallpaperRedraw() {
        var lines by mutableStateOf(1)
        var wallpaper by mutableStateOf(Color(0xFF204F70))
        var style by mutableStateOf(UiMaterialStyle.FOG_GLASS)
        var dark by mutableStateOf(false)
        compose.setContent {
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(
                    LocalThemeMaterial provides ThemeMaterialSetting(style),
                    LocalDarkMode provides dark,
                ) {
                    MaterialBackdrop(
                        background = {
                            Box(Modifier.fillMaxSize().background(wallpaper))
                        }
                    ) {
                        Box(Modifier.padding(24.dp).testTag("bubble")) {
                            BubbleSurface("", 20.dp, scheme.surface, false, 1f) {
                                Box(
                                    Modifier.width(240.dp)
                                        .height((40 + lines * 16).dp)
                                        .testTag("streamed-content")
                                ) {
                                    Text("流式文字 $lines", color = Color.Black)
                                    Box(
                                        Modifier.padding(top = 32.dp)
                                            .size(12.dp)
                                            .background(Color.Black)
                                            .testTag("foreground-ink")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            for (material in
                listOf(
                    UiMaterialStyle.FOG_GLASS,
                    UiMaterialStyle.FROSTED,
                    UiMaterialStyle.LIQUID_GLASS,
                )) {
                for (isDark in listOf(false, true)) {
                    compose.runOnIdle {
                        style = material
                        dark = isDark
                    }
                    for (count in listOf(1, 4, 2, 7, 3, 8)) {
                        compose.runOnIdle {
                            lines = count
                            wallpaper = if (count % 2 == 0) Color(0xFF782C45) else Color(0xFF204F70)
                        }
                        // Inspect the next frame, not the settled size-tween result.
                        compose.mainClock.advanceTimeByFrame()
                        compose.waitForIdle()
                        val bubble =
                            compose.onNodeWithTag("bubble").fetchSemanticsNode().boundsInRoot
                        val content =
                            compose
                                .onNodeWithTag("streamed-content")
                                .fetchSemanticsNode()
                                .boundsInRoot
                        assertTrue(
                            "Streaming content was clipped by an interrupted size tween",
                            content.bottom <= bubble.bottom + 1f,
                        )
                        val pixels = compose.onRoot().captureToImage().toPixelMap()
                        val sample = pixels[(content.right - 8).toInt(), (content.top + 30).toInt()]
                        val tint = (if (isDark) darkColorScheme() else lightColorScheme()).surface
                        val alpha =
                            ThemeMaterialSetting(material)
                                .profile(isDark, MaterialRole.BUBBLE)
                                .tintAlpha
                        assertColor(tint.copy(alpha = alpha).compositeOver(wallpaper), sample)
                        val ink =
                            compose
                                .onNodeWithTag("foreground-ink")
                                .fetchSemanticsNode()
                                .boundsInRoot
                        assertColor(Color.Black, pixels[ink.center.x.toInt(), ink.center.y.toInt()])
                    }
                }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun settingsAndChatKeepTheirLoadedWallpaperWhenNavigationEntriesAreRecreated() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("retained-wallpaper-", ".png", context.cacheDir)
        val color = Color(0xFF275070)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color.toArgb())
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        val stack = mutableStateListOf<NavKey>(Screen.Setting)
        var loaded: AsyncImagePainter? = null
        try {
            compose.setContent {
                MaterialTheme {
                    val painters = rememberWallpapers(listOf(file.absolutePath))
                    loaded = painters.getValue(file.absolutePath) as AsyncImagePainter
                    CompositionLocalProvider(LocalWallpapers provides painters) {
                        Box(Modifier.fillMaxSize()) {
                            SettingsWallpaper(file.absolutePath)
                            NavDisplay(
                                backStack = stack,
                                entryDecorators =
                                    listOf(
                                        rememberSettingsBackdropDecorator(
                                            DisplaySetting(
                                                settingsBackgroundPath = file.absolutePath
                                            )
                                        )
                                    ),
                                transitionSpec = {
                                    fadeIn(tween(256)) togetherWith fadeOut(tween(256))
                                },
                                popTransitionSpec = {
                                    fadeIn(tween(256)) togetherWith fadeOut(tween(256))
                                },
                                onBack = { stack.removeLastOrNull() },
                                entryProvider =
                                    entryProvider<NavKey> {
                                            entry<Screen.Setting> { Text("设置") }
                                            entry<Screen.SettingDisplayTheme> { Text("子页面") }
                                            entry<Screen.Chat> {
                                                WallpaperImage(
                                                    file.absolutePath,
                                                    Modifier.fillMaxSize(),
                                                )
                                            }
                                        }
                                        .withSettingsBackdropMetadata(),
                            )
                        }
                    }
                }
            }
            compose.waitUntil(5_000) { loaded?.state?.value is AsyncImagePainter.State.Success }
            val firstPainter = loaded
            repeat(3) {
                compose.runOnIdle { stack.add(Screen.SettingDisplayTheme) }
                compose.waitForIdle()
                compose.mainClock.autoAdvance = false
                compose.runOnIdle { stack.removeLastOrNull() }
                repeat(18) {
                    compose.mainClock.advanceTimeByFrame()
                    val pixels = compose.onRoot().captureToImage().toPixelMap()
                    assertColor(color, pixels[pixels.width / 2, pixels.height * 3 / 4])
                }
                compose.mainClock.autoAdvance = true
                compose.runOnIdle { stack.add(Screen.Chat("fixture")) }
                compose.waitForIdle()
                compose.runOnIdle { stack.removeLastOrNull() }
                compose.waitForIdle()
                assertSame(
                    "Returning to a page recreated its wallpaper request",
                    firstPainter,
                    loaded,
                )
                assertTrue(
                    "Wallpaper returned to an empty/loading state",
                    loaded?.state?.value is AsyncImagePainter.State.Success,
                )
                val pixels = compose.onRoot().captureToImage().toPixelMap()
                assertColor(color, pixels[pixels.width / 2, pixels.height * 3 / 4])
            }
        } finally {
            compose.mainClock.autoAdvance = true
            file.delete()
        }
    }

    private fun assertColor(expected: Color, actual: Color) {
        assertEquals("red: $actual", expected.red, actual.red, 0.05f)
        assertEquals("green: $actual", expected.green, actual.green, 0.05f)
        assertEquals("blue: $actual", expected.blue, actual.blue, 0.05f)
    }
}
