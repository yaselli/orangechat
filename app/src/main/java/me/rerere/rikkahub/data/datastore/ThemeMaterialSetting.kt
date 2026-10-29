package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
enum class UiMaterialStyle {
    ORIGINAL,
    LIQUID_GLASS,
    FROSTED,
    FOG_GLASS,
}

/** Material and palette are independent. Larger transparency values mean less surface tint. */
@Serializable
data class ThemeMaterialSetting(
    val style: UiMaterialStyle = UiMaterialStyle.FOG_GLASS,
    val fogTransparency: Float = 0.80f,
) {
    val safeFogTransparency: Float
        get() = if (fogTransparency.isFinite()) fogTransparency.coerceIn(0f, 1f) else 0.80f
}
