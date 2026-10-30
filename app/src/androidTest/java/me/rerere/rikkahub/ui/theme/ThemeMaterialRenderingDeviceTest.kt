package me.rerere.rikkahub.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.io.File
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.ThemeMaterialSetting
import me.rerere.rikkahub.data.datastore.UiMaterialStyle
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.pages.chat.AssistantBackground
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
                if (pixel.alpha > 0.5f && maxOf(pixel.red, pixel.green, pixel.blue) < 0.5f) ink++
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

    @Test
    fun wallpaperKeepsItsOriginalColorsAndOnlyUsesExplicitUserOpacity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("wallpaper-colors-", ".png", context.cacheDir)
        val imageColor = Color(0xFF123456)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(imageColor.toArgb())
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        val assistant = Assistant(background = file.toURI().toString())
        var style by mutableStateOf(UiMaterialStyle.FOG_GLASS)
        var dark by mutableStateOf(false)
        var opacity by mutableStateOf(1f)
        try {
            compose.setContent {
                val material = ThemeMaterialSetting(style)
                val scheme =
                    if (dark) darkColorScheme(background = Color.Black)
                    else lightColorScheme(background = Color.White)
                MaterialTheme(colorScheme = scheme.withThemeMaterial(material, dark)) {
                    CompositionLocalProvider(LocalThemeMaterial provides material) {
                        MaterialBackdrop(
                            background = {
                                AssistantBackground(
                                    Settings(
                                        assistantId = assistant.id,
                                        assistants =
                                            listOf(assistant.copy(backgroundOpacity = opacity)),
                                    )
                                )
                            }
                        ) {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
            // Wait for Coil to decode the fixture. A blanket tint over the image cannot pass.
            compose.waitUntil(timeoutMillis = 5_000) {
                val pixels = compose.onRoot().captureToImage().toPixelMap()
                val actual = pixels[pixels.width / 2, pixels.height / 2]
                kotlin.math.abs(actual.red - imageColor.red) < 0.015f &&
                    kotlin.math.abs(actual.blue - imageColor.blue) < 0.015f
            }
            for (material in UiMaterialStyle.entries) {
                for (isDark in listOf(false, true)) {
                    for (alpha in listOf(1f, 0.5f)) {
                        compose.runOnIdle {
                            style = material
                            dark = isDark
                            opacity = alpha
                        }
                        val base = if (isDark) Color.Black else Color.White
                        assertBackdrop(imageColor.copy(alpha = alpha).compositeOver(base))
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun drawerLayerTransitionsNeverChangeWallpaperOutsideMaterialBounds() {
        var progress by mutableStateOf(0f)
        var style by mutableStateOf(materials.first())
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompositionLocalProvider(
                    LocalThemeMaterial provides ThemeMaterialSetting(style),
                    LocalMaterialBlurEnabled provides true,
                ) {
                    MaterialBackdrop(
                        background = {
                            Canvas(Modifier.fillMaxSize()) {
                                val stripe = 11.dp.toPx()
                                for (index in 0..(size.width / stripe).toInt()) {
                                    drawRect(
                                        color =
                                            if (index % 2 == 0) Color(0xFF8DB6D4)
                                            else Color(0xFFEBF4FA),
                                        topLeft = Offset(index * stripe, 0f),
                                        size = Size(stripe, size.height),
                                    )
                                }
                            }
                        }
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .then(
                                    if (progress > 0.001f)
                                        Modifier.graphicsLayer {
                                            translationX = 108.dp.toPx() * progress
                                            scaleX = 1f - 0.075f * progress
                                            scaleY = scaleX
                                            rotationY = -10.5f * progress
                                            cameraDistance = 22f * density
                                            transformOrigin = TransformOrigin(0f, 0.5f)
                                            shape = RoundedCornerShape(28.dp)
                                            clip = true
                                        }
                                    else Modifier
                                )
                        ) {
                            Box(
                                Modifier.padding(24.dp)
                                    .size(160.dp, 72.dp)
                                    .themeMaterial(
                                        shape = RoundedCornerShape(20.dp),
                                        tint = MaterialTheme.colorScheme.surface,
                                        role = MaterialRole.BUBBLE,
                                    )
                            ) {
                                Text("气泡文字", Modifier.padding(12.dp))
                            }
                        }
                        Box(
                            Modifier.offset {
                                    IntOffset((-180.dp.toPx() * (1f - progress)).toInt(), 0)
                                }
                                .width(180.dp)
                                .fillMaxHeight()
                                .themeMaterial(
                                    shape = RoundedCornerShape(24.dp),
                                    tint = MaterialTheme.colorScheme.surface,
                                    role = MaterialRole.DRAWER,
                                )
                        )
                    }
                }
            }
        }
        val initial = compose.onRoot().captureToImage().toPixelMap()
        // These points stay outside both the moving bubble and the drawer, even at full opening.
        val positions = listOf(0.88f to 0.70f, 0.93f to 0.82f, 0.97f to 0.91f)
        for (material in materials) {
            compose.runOnIdle { style = material }
            repeat(2) {
                for (fraction in listOf(0f, 0.25f, 0.8f, 1f, 0.5f, 0f)) {
                    compose.runOnIdle { progress = fraction }
                    compose.mainClock.advanceTimeBy(100)
                    compose.waitForIdle()
                    val pixels = compose.onRoot().captureToImage().toPixelMap()
                    for ((x, y) in positions) {
                        val px = (pixels.width * x).toInt()
                        val py = (pixels.height * y).toInt()
                        val expected = initial[px, py]
                        val actual = pixels[px, py]
                        val context = "$material drawer=$fraction at $px,$py"
                        assertEquals(context, expected.red, actual.red, 0.015f)
                        assertEquals(context, expected.green, actual.green, 0.015f)
                        assertEquals(context, expected.blue, actual.blue, 0.015f)
                    }
                }
            }
        }
    }
}
