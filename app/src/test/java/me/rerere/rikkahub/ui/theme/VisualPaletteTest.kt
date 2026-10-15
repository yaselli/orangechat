package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.VisualThemePalette
import org.junit.Assert.*
import org.junit.Test

class VisualPaletteTest {
    @Test
    fun `new palettes remain readable in both color modes`() {
        for (palette in listOf(VisualThemePalette.APRICOT_CREAM, VisualThemePalette.MIST_BLUE)) {
            for (dark in listOf(false, true)) {
                val colors = lightColorScheme().withVisualPalette(palette, dark)
                val pairs = listOf(
                    colors.onPrimary to colors.primary,
                    colors.onPrimaryContainer to colors.primaryContainer,
                    colors.onSecondary to colors.secondary,
                    colors.onSecondaryContainer to colors.secondaryContainer,
                    colors.onTertiary to colors.tertiary,
                    colors.onTertiaryContainer to colors.tertiaryContainer,
                    colors.onBackground to colors.background,
                    colors.onSurface to colors.surface,
                    colors.onSurfaceVariant to colors.surfaceVariant,
                    colors.onSurface to colors.surfaceContainerHighest,
                )
                for ((text, surface) in pairs) {
                    assertTrue("$palette dark=$dark: insufficient text contrast", contrast(text, surface) >= 4.5f)
                }
                assertEquals(1f, colors.surface.alpha, 0.001f)
                assertEquals(1f, colors.background.alpha, 0.001f)
            }
        }
    }

    @Test
    fun `existing default remains sea salt and original preserves base colors`() {
        assertEquals(VisualThemePalette.SEA_SALT, DisplaySetting().visualThemePalette)
        val base = lightColorScheme()
        assertSame(base, base.withVisualPalette(VisualThemePalette.ORIGINAL, false))
        val seaSalt = base.withVisualPalette(VisualThemePalette.SEA_SALT, false)
        assertEquals(Color(0xFF4F858A), seaSalt.primary)
        assertEquals(Color(0xFFF5F9F9), seaSalt.background)
    }

    @Test
    fun `new palette choices survive settings serialization without changing other options`() {
        for (palette in listOf(VisualThemePalette.APRICOT_CREAM, VisualThemePalette.MIST_BLUE)) {
            val original = DisplaySetting(
                visualThemePalette = palette,
                enableGlassDrawer = true,
                inputBackgroundPath = "/custom/input.png",
                drawerBackgroundPath = "/custom/drawer.png",
            )
            val restored = Json.decodeFromString<DisplaySetting>(Json.encodeToString(original))
            assertEquals(original, restored)
        }
    }

    private fun contrast(text: Color, surface: Color): Float {
        val a = text.luminance()
        val b = surface.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
}
