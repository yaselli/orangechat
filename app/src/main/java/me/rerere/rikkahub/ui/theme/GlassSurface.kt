package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shared static glass treatment for bubbles, input, drawer and settings.
 * Draws cached gradients only; background blur remains owned by existing Haze surfaces.
 * Inspired by watermio123-droid/orangechat liquid-glass-ui (AGPL-3.0).
 */
internal fun Modifier.glassSurface(
    shape: Shape,
    tint: Color,
    dark: Boolean,
    opacity: Float = 1f,
    drawFill: Boolean = true,
    borderColor: Color? = null,
): Modifier {
    val strength = opacity.coerceIn(0f, 1f)
    val edge = borderColor ?: Color.White.copy(alpha = (if (dark) 0.16f else 0.46f) * strength)
    return clip(shape)
        .drawWithCache {
            val fill = Brush.verticalGradient(
                colors = listOf(
                    tint.copy(alpha = (if (dark) 0.80f else 0.72f) * strength),
                    tint.copy(alpha = (if (dark) 0.68f else 0.46f) * strength),
                    tint.copy(alpha = (if (dark) 0.74f else 0.58f) * strength),
                ),
            )
            val depth = minOf(12.dp.toPx(), size.height)
            val highlight = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = (if (dark) 0.08f else 0.24f) * strength),
                    Color.Transparent,
                ),
                endY = depth.coerceAtLeast(1f),
            )
            val rim = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = (if (dark) 0.24f else 0.56f) * strength),
                    Color.Transparent,
                ),
            )
            onDrawBehind {
                if (size.width > 0f && size.height > 0f) {
                    if (drawFill) drawRect(fill)
                    drawRect(highlight, size = Size(size.width, depth))
                    val inset = minOf(18.dp.toPx(), size.width / 4f)
                    drawLine(
                        brush = rim,
                        start = Offset(inset, 0.8.dp.toPx()),
                        end = Offset(size.width - inset, 0.8.dp.toPx()),
                        strokeWidth = 0.8.dp.toPx(),
                    )
                }
            }
        }
        .border(0.8.dp, edge, shape)
}
