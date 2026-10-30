package me.rerere.rikkahub.ui.theme

import me.rerere.rikkahub.data.datastore.ThemeMaterialSetting
import me.rerere.rikkahub.data.datastore.UiMaterialStyle

internal enum class MaterialRole {
    BUBBLE,
    INPUT,
    CARD,
    DRAWER,
    DIALOG,
}

/** Optical properties only; no palette, bitmap, mutable state, or rendering resources. */
internal data class ThemeMaterialProfile(
    val tintAlpha: Float,
    val blurRadius: Float,
    val noise: Float,
    val edgeAlpha: Float,
    val highlightAlpha: Float,
    val elevation: Float,
)

internal fun ThemeMaterialSetting.profile(
    dark: Boolean,
    role: MaterialRole,
    softDark: Boolean = false,
): ThemeMaterialProfile {
    val base =
        when (style) {
            UiMaterialStyle.ORIGINAL -> ThemeMaterialProfile(1f, 0f, 0f, 0f, 0f, 0f)
            UiMaterialStyle.FOG_GLASS ->
                ThemeMaterialProfile(
                    tintAlpha =
                        (1f - safeFogTransparency + if (dark) 0.18f else 0f).coerceIn(0f, 1f),
                    blurRadius = 18f,
                    noise = 0f,
                    edgeAlpha = if (dark) 0.20f else 0.48f,
                    highlightAlpha = if (dark) 0.025f else 0.08f,
                    elevation = 3f,
                )
            UiMaterialStyle.FROSTED ->
                ThemeMaterialProfile(
                    tintAlpha = if (dark) 0.86f else 0.73f,
                    blurRadius = 25f,
                    noise = 0.015f,
                    edgeAlpha = if (dark) 0.10f else 0.24f,
                    highlightAlpha = 0.015f,
                    elevation = 2f,
                )
            UiMaterialStyle.LIQUID_GLASS ->
                ThemeMaterialProfile(
                    tintAlpha = if (dark) 0.49f else 0.30f,
                    blurRadius = 3f,
                    noise = 0f,
                    edgeAlpha = if (dark) 0.45f else 0.82f,
                    highlightAlpha = if (dark) 0.13f else 0.32f,
                    elevation = 5f,
                )
        }
    if (style == UiMaterialStyle.ORIGINAL) return base
    val result =
        when (role) {
            MaterialRole.BUBBLE,
            MaterialRole.INPUT -> base
            MaterialRole.CARD ->
                base.copy(tintAlpha = maxOf(base.tintAlpha, if (dark) 0.58f else 0.35f))
            MaterialRole.DRAWER ->
                base.copy(
                    tintAlpha = maxOf(base.tintAlpha, if (dark) 0.85f else 0.50f),
                    blurRadius = if (style == UiMaterialStyle.LIQUID_GLASS) 12f else 24f,
                    elevation = 12f,
                )
            // Popups live in separate windows; use a readable static material instead of sampling
            // a Haze source from another window. Modal surfaces are intentionally thicker.
            MaterialRole.DIALOG ->
                base.copy(
                    tintAlpha = maxOf(base.tintAlpha, if (dark) 0.97f else 0.94f),
                    blurRadius = 0f,
                )
        }
    if (!softDark) return result
    // White text must remain readable over pale photographs in the optional soft dark mode.
    val minimum =
        when (role) {
            MaterialRole.BUBBLE,
            MaterialRole.INPUT -> 0.72f
            MaterialRole.CARD -> 0.80f
            MaterialRole.DRAWER -> 0.86f
            MaterialRole.DIALOG -> 0.97f
        }
    return result.copy(tintAlpha = maxOf(result.tintAlpha, minimum))
}
