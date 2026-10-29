package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ThemeMaterialSetting
import me.rerere.rikkahub.data.datastore.UiMaterialStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class ThemeMaterialRenderingDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val materials =
        listOf(UiMaterialStyle.FOG_GLASS, UiMaterialStyle.FROSTED, UiMaterialStyle.LIQUID_GLASS)

    @Test
    fun legacyGreenInputCannotTintUnifiedMaterialsInLightDarkOrStaticRendering() {
        var style by mutableStateOf(materials.first())
        var dark by mutableStateOf(false)
        var blur by mutableStateOf(true)
        compose.setContent {
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(
                    LocalThemeMaterial provides ThemeMaterialSetting(style),
                    LocalDarkMode provides dark,
                    LocalMaterialBlurEnabled provides blur,
                ) {
                    MaterialBackdrop(background = { ThemeMaterialBackground() }) {
                        Box(
                            Modifier.padding(24.dp)
                                .size(280.dp, 120.dp)
                                .testTag("material-input")
                                .themeMaterial(
                                    RoundedCornerShape(20.dp),
                                    scheme.inputMaterialTint(style, Color.Green),
                                    role = MaterialRole.INPUT,
                                )
                        ) {
                            Text("输入消息", Modifier.padding(12.dp), color = scheme.onSurface)
                        }
                    }
                }
            }
        }
        for (material in materials) {
            for (isDark in listOf(false, true)) {
                for (live in listOf(true, false)) {
                    compose.runOnIdle {
                        style = material
                        dark = isDark
                        blur = live
                    }
                    compose.mainClock.advanceTimeBy(500)
                    compose.waitForIdle()
                    compose.onNodeWithText("输入消息").assertIsDisplayed()
                    val pixels =
                        compose.onNodeWithTag("material-input").captureToImage().toPixelMap()
                    val pixel = pixels[pixels.width / 2, pixels.height / 2]
                    assertTrue(
                        "$material dark=$isDark blur=$live: $pixel",
                        pixel.green - maxOf(pixel.red, pixel.blue) < 0.10f,
                    )
                }
            }
        }
        // Saved colors remain available when the user explicitly returns to original appearance.
        assertEquals(
            Color.Green,
            lightColorScheme().inputMaterialTint(UiMaterialStyle.ORIGINAL, Color.Green),
        )
    }

    @Test
    fun reasoningIconContainsTheSketchesHighDiagonalTip() {
        compose.setContent {
            Box(Modifier.background(Color.White)) {
                Icon(
                    painterResource(R.drawable.ic_watermelon_thinking),
                    contentDescription = "思考图标",
                    tint = Color.Black,
                    modifier = Modifier.size(48.dp).testTag("reasoning-icon"),
                )
            }
        }
        val pixels = compose.onNodeWithTag("reasoning-icon").captureToImage().toPixelMap()
        var ink = 0
        for (y in 0 until (pixels.height * 0.18f).toInt()) {
            for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                if (pixel.alpha > 0.5f && maxOf(pixel.red, pixel.green, pixel.blue) < 0.5f)
                    ink++
            }
        }
        // The former horizontal slice had no path above viewport y=5.65 / 24.
        assertTrue("The old horizontal reasoning icon was packaged", ink > 0)
    }

    @Test
    fun switchingScreensAndRestoringWallpaperNeverDrawsAccentCircles() {
        val base = Color(0xFFF5F7FA)
        val wallpaper = Color(0xFFDDE9F4)
        var style by mutableStateOf(materials.first())
        var chat by mutableStateOf(false)
        var imageReady by mutableStateOf(false)
        compose.setContent {
            // Deliberately vivid accents would reveal the former background decorations.
            MaterialTheme(
                colorScheme =
                    lightColorScheme(background = base, primary = Color.Green, tertiary = Color.Red)
            ) {
                CompositionLocalProvider(LocalThemeMaterial provides ThemeMaterialSetting(style)) {
                    MaterialBackdrop(background = { ThemeMaterialBackground() }) {
                        if (chat) {
                            MaterialBackdrop(
                                background = {
                                    ThemeMaterialBackground()
                                    if (imageReady)
                                        Box(Modifier.fillMaxSize().background(wallpaper))
                                }
                            ) {
                                Box(Modifier.fillMaxSize())
                            }
                        } else Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        for (material in materials) {
            compose.runOnIdle {
                style = material
                chat = false
                imageReady = false
            }
            assertBackdrop(base)
            compose.runOnIdle { chat = true }
            assertBackdrop(base)
            compose.runOnIdle { imageReady = true }
            assertBackdrop(wallpaper)
            compose.runOnIdle { chat = false }
            assertBackdrop(base)
            compose.runOnIdle {
                chat = true
                imageReady = false
            }
            assertBackdrop(base)
        }
    }

    private fun assertBackdrop(expected: Color) {
        compose.waitForIdle()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        for ((x, y) in listOf(0.10f to 0.17f, 0.50f to 0.50f, 0.90f to 0.60f, 0.80f to 0.85f)) {
            val actual = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()]
            assertEquals(expected.red, actual.red, 0.015f)
            assertEquals(expected.green, actual.green, 0.015f)
            assertEquals(expected.blue, actual.blue, 0.015f)
        }
    }
}
