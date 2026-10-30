package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

internal val LocalSoftDarkMode = compositionLocalOf { false }

/** A separate, explicitly selected appearance; existing dark and AMOLED modes stay independent. */
internal fun ColorScheme.withSoftDarkAppearance(): ColorScheme =
    copy(
        background = Color(0xFF3D4540),
        surface = Color(0xFF424B45),
        surfaceDim = Color(0xFF343C37),
        surfaceBright = Color(0xFF535D55),
        surfaceContainerLowest = Color(0xFF343C37),
        surfaceContainerLow = Color(0xFF3D463F),
        surfaceContainer = Color(0xFF454F47),
        surfaceContainerHigh = Color(0xFF4D584F),
        surfaceContainerHighest = Color(0xFF566159),
        onBackground = Color(0xFFF1F4EE),
        onSurface = Color(0xFFF1F4EE),
        onSurfaceVariant = Color(0xFFE6ECE2),
        outline = Color(0xFFA9B6A8),
        outlineVariant = Color(0xFF727E72),
    )

/** Only the requested soft dark mode dims photographs; light mode never adds a veil. */
@Composable
internal fun SoftDarkWallpaperShade() {
    if (LocalSoftDarkMode.current) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
    }
}
