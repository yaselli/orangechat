package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade

internal val LocalWallpapers = compositionLocalOf<Map<String, Painter>> { emptyMap() }

/** Keep the two active wallpapers loaded even while their navigation entries are off screen. */
@Composable
internal fun rememberWallpapers(models: List<String?>): Map<String, Painter> =
    models
        .filterNotNull()
        .filter { it.isNotBlank() }
        .distinct()
        .associateWith { model ->
            key(model) { rememberWallpaperPainter(model) }
        }

@Composable
private fun rememberWallpaperPainter(model: String): Painter {
    val context = LocalContext.current
    val size = LocalWindowInfo.current.containerSize
    val request =
        remember(context, model, size) {
            ImageRequest.Builder(context)
                .data(model)
                // Wallpaper entry/exit already animates with navigation. A second image fade
                // exposes the plain background, and also changes every glass surface's source.
                .crossfade(false)
                .size(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
                .build()
        }
    return rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
}

@Composable
internal fun WallpaperImage(model: String, modifier: Modifier = Modifier) {
    val painter = LocalWallpapers.current[model] ?: rememberWallpaperPainter(model)
    Image(painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}
