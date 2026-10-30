package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.ThemeMaterialSetting
import me.rerere.rikkahub.data.datastore.UiMaterialStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalAppearanceTest {
    @Test
    fun softDarkIsGrayReadableAndPreservesAccentAndStatusColors() {
        val original = darkColorScheme(background = Color.Black)
        val soft = original.withSoftDarkAppearance()
        assertNotEquals(Color.Black, soft.background)
        assertEquals(original.primary, soft.primary)
        assertEquals(original.error, soft.error)
        for (surface in
            listOf(
                soft.background,
                soft.surface,
                soft.surfaceBright,
                soft.surfaceContainerHighest,
            )) {
            val contrast = (soft.onSurface.luminance() + 0.05f) / (surface.luminance() + 0.05f)
            assertTrue("Unreadable text contrast $contrast on $surface", contrast >= 4.5f)
        }
        assertEquals(
            setOf("SYSTEM", "LIGHT", "DARK", "SOFT_DARK"),
            ColorMode.entries.map { it.name }.toSet(),
        )
        val photo = Color.Black.copy(alpha = 0.28f).compositeOver(Color.White)
        for (style in UiMaterialStyle.entries.filter { it != UiMaterialStyle.ORIGINAL }) {
            val alpha =
                ThemeMaterialSetting(style)
                    .profile(true, MaterialRole.CARD, softDark = true)
                    .tintAlpha * 0.88f
            val card = soft.surface.copy(alpha = alpha).compositeOver(photo)
            assertTrue(
                (soft.onSurfaceVariant.luminance() + 0.05f) / (card.luminance() + 0.05f) >= 4.5f
            )
        }
    }

    @Test
    fun everySettingsRouteUsesTheSharedWallpaperIncludingDetails() {
        val types =
            Screen::class.java.declaredClasses.filter { it.simpleName.startsWith("Setting") }
        assertTrue(types.isNotEmpty())
        for (type in types) {
            val instance =
                runCatching { type.getField("INSTANCE").get(null) }
                    .getOrElse {
                        type.getConstructor(String::class.java).newInstance("fixture")
                    } as Screen
            assertTrue(
                "Missing wallpaper scope for ${type.simpleName}",
                instance.usesSettingsBackdrop,
            )
        }
        assertTrue(Screen.AssistantMemory("fixture").usesSettingsBackdrop)
        assertTrue(Screen.Backup.usesSettingsBackdrop)
    }

    @Test
    fun settingsWallpaperDoesNotReplaceChatHistoryOrCallBackgrounds() {
        assertFalse(Screen.Chat("fixture").usesSettingsBackdrop)
        assertFalse(Screen.History.usesSettingsBackdrop)
        assertFalse(Screen.Favorite.usesSettingsBackdrop)
        assertFalse(Screen.VoiceCall("fixture").usesSettingsBackdrop)
        assertFalse(Screen.ImageGen.usesSettingsBackdrop)
    }
}
