package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.rikkahub.data.datastore.ThemeMaterialSetting
import me.rerere.rikkahub.data.datastore.UiMaterialStyle

internal val LocalThemeMaterial = compositionLocalOf {
    ThemeMaterialSetting(style = UiMaterialStyle.ORIGINAL)
}
internal val LocalMaterialBackdrop = compositionLocalOf<HazeState?> { null }
internal val LocalMaterialBlurEnabled = compositionLocalOf { true }

/** Capture only the wallpaper, never the material/content which samples it. */
@Composable
internal fun MaterialBackdrop(
    background: @Composable () -> Unit,
    captureBackground: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberHazeState()
    val active =
        captureBackground && LocalThemeMaterial.current.style != UiMaterialStyle.ORIGINAL &&
            LocalMaterialBlurEnabled.current
    val capture = LocalScrollCaptureInProgress.current
    CompositionLocalProvider(
        LocalMaterialBackdrop provides if (active && !capture) state else null
    ) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 1f))
        ) {
            Box(
                Modifier.fillMaxSize()
                    .then(if (active && !capture) Modifier.hazeSource(state) else Modifier)
            ) {
                background()
            }
            content()
        }
    }
}

@Composable
internal fun ThemeMaterialBackground() {
    // A material changes surfaces, not the user's wallpaper. Keep the initial frame and
    // uncovered areas plain while an image loads or navigation restores the chat screen.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 1f)))
}

/** Legacy per-input colors belong to the original appearance, not unified theme materials. */
internal fun ColorScheme.inputMaterialTint(style: UiMaterialStyle, customColor: Color?): Color =
    if (style == UiMaterialStyle.ORIGINAL) customColor ?: surfaceContainerLow
    else surface.copy(alpha = 1f)

/** One implementation for all material surfaces. Does not blur text or foreground controls. */
@Composable
internal fun Modifier.themeMaterial(
    shape: Shape,
    tint: Color,
    role: MaterialRole = MaterialRole.CARD,
    opacity: Float = 1f,
    drawFill: Boolean = true,
    hazeState: HazeState? = LocalMaterialBackdrop.current,
    borderColor: Color? = null,
): Modifier {
    val material = LocalThemeMaterial.current
    if (material.style == UiMaterialStyle.ORIGINAL) return this
    val dark = LocalDarkMode.current
    val profile = material.profile(dark, role, LocalSoftDarkMode.current)
    val contextualOpacity = if (role == MaterialRole.DIALOG) 1f else LocalMaterialSurfaceOpacity.current
    val strength = (if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else 1f) * contextualOpacity
    val capture = LocalScrollCaptureInProgress.current
    val useHaze =
        hazeState != null &&
            drawFill &&
            !capture &&
            LocalMaterialBlurEnabled.current &&
            role != MaterialRole.DIALOG
    val background = MaterialTheme.colorScheme.background.copy(alpha = 1f)
    val hazeStyle =
        remember(profile, tint, background, strength) {
            HazeStyle(
                backgroundColor = background,
                tints = listOf(HazeTint(tint.copy(alpha = profile.tintAlpha * strength))),
                blurRadius = profile.blurRadius.dp,
                noiseFactor = profile.noise,
                fallbackTint =
                    HazeTint(tint.copy(alpha = maxOf(profile.tintAlpha, 0.70f) * strength)),
            )
        }
    // Keep each sampled surface in its own bounded render target. In particular, a
    // drawer's moving/3D parent layer must not become the target for a bubble's blur.
    // Static/export paths retain the cheaper ordinary shape clip.
    val surfaceLayer =
        if (useHaze)
            Modifier.graphicsLayer {
                this.shape = shape
                clip = true
                compositingStrategy = CompositingStrategy.Offscreen
            }
        else Modifier.clip(shape)
    return then(surfaceLayer)
        .then(
            if (useHaze) Modifier.hazeEffect(state = hazeState!!, style = hazeStyle) else Modifier
        )
        .drawWithCache {
            val fill =
                Brush.linearGradient(
                    listOf(
                        tint.copy(alpha = profile.tintAlpha * strength),
                        tint.copy(alpha = (profile.tintAlpha * 0.92f) * strength),
                    )
                )
            val depth =
                minOf(
                    size.height,
                    if (material.style == UiMaterialStyle.LIQUID_GLASS) 18.dp.toPx()
                    else 10.dp.toPx(),
                )
            val highlight =
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = profile.highlightAlpha * strength),
                        Color.Transparent,
                    ),
                    endY = depth.coerceAtLeast(1f),
                )
            val rim =
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = profile.edgeAlpha * strength),
                        Color.Transparent,
                    )
                )
            onDrawBehind {
                if (size.width > 0 && size.height > 0) {
                    if (drawFill && !useHaze) drawRect(fill)
                    drawRect(highlight, size = Size(size.width, depth))
                    if (material.style == UiMaterialStyle.LIQUID_GLASS) {
                        val inset = minOf(14.dp.toPx(), size.width / 4)
                        drawLine(
                            rim,
                            Offset(inset, 1.dp.toPx()),
                            Offset(size.width - inset, 1.dp.toPx()),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }
            }
        }
        .border(
            if (material.style == UiMaterialStyle.LIQUID_GLASS) 1.dp else 0.6.dp,
            borderColor ?: Color.White.copy(alpha = profile.edgeAlpha * strength),
            shape,
        )
}

internal fun ColorScheme.withThemeMaterial(
    material: ThemeMaterialSetting,
    dark: Boolean,
): ColorScheme {
    if (material.style == UiMaterialStyle.ORIGINAL) return this
    val card = material.profile(dark, MaterialRole.CARD).tintAlpha
    val modal = material.profile(dark, MaterialRole.DIALOG).tintAlpha
    return copy(
        background = background.copy(alpha = 0f),
        surface = surface.copy(alpha = modal),
        surfaceBright = surfaceBright.copy(alpha = card),
        surfaceDim = surfaceDim.copy(alpha = modal),
        surfaceContainerLowest = surfaceContainerLowest.copy(alpha = card),
        // Material 3 sheets and menus also use these tokens. Keep their defaults readable;
        // explicit themeMaterial surfaces own their thinner optical treatment.
        surfaceContainerLow = surfaceContainerLow.copy(alpha = modal),
        surfaceContainer = surfaceContainer.copy(alpha = modal),
        surfaceContainerHigh = surfaceContainerHigh.copy(alpha = modal),
        surfaceContainerHighest = surfaceContainerHighest.copy(alpha = modal),
    )
}
