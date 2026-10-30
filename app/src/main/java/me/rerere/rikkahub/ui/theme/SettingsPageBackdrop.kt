package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import coil3.compose.AsyncImage
import java.io.File
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.DisplaySetting

private const val SETTINGS_BACKDROP_METADATA = "watermelon.settingsBackdrop"

/** Resolve route policy while the provider still has the public route key. */
internal fun <T : NavKey> ((T) -> NavEntry<T>).withSettingsBackdropMetadata(): (T) -> NavEntry<T> =
    { key ->
        val entry = invoke(key)
        NavEntry(
            key = key,
            contentKey = entry.contentKey,
            metadata =
                entry.metadata +
                    (SETTINGS_BACKDROP_METADATA to
                        ((key as? Screen)?.usesSettingsBackdrop == true)),
        ) {
            entry.Content()
        }
    }

internal val LocalMaterialSurfaceOpacity = compositionLocalOf { 1f }
internal val LocalSettingsWallpaperActive = compositionLocalOf { false }

@Composable
internal fun rememberSettingsBackdropDecorator(display: DisplaySetting): NavEntryDecorator<NavKey> {
    val currentDisplay by rememberUpdatedState(display)
    return remember {
        NavEntryDecorator { entry ->
            if (entry.metadata[SETTINGS_BACKDROP_METADATA] == true) {
                SettingsPageBackdrop(currentDisplay) { entry.Content() }
            } else entry.Content()
        }
    }
}

/** Navigation-entry scoped: both pages retain their own wallpaper during push/pop animations. */
@Composable
internal fun SettingsPageBackdrop(
    display: DisplaySetting,
    content: @Composable BoxScope.() -> Unit,
) {
    val wallpaper = display.settingsBackgroundPath.takeIf { it.isNotBlank() && File(it).isFile }
    val opacity = display.settingsUiAlpha.takeIf { it.isFinite() }?.coerceIn(0.25f, 1f) ?: 0.88f
    val scheme = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme =
            if (wallpaper == null) scheme
            else scheme.copy(background = scheme.background.copy(alpha = 0f))
    ) {
        CompositionLocalProvider(
            LocalMaterialSurfaceOpacity provides opacity,
            LocalSettingsWallpaperActive provides (wallpaper != null),
        ) {
            MaterialBackdrop(
                background = {
                    ThemeMaterialBackground()
                    if (wallpaper != null) {
                        AsyncImage(
                            model = File(wallpaper),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        SoftDarkWallpaperShade()
                    }
                },
                captureBackground = wallpaper != null,
                content = content,
            )
        }
    }
}
