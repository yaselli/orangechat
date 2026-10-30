package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeMaterialSettingTest {
    @Test
    fun `missing new configuration uses approved transparent fog glass`() {
        val material = Json.decodeFromString<ThemeMaterialSetting>("{}")
        assertEquals(UiMaterialStyle.FOG_GLASS, material.style)
        assertEquals(0.80f, material.safeFogTransparency, 0.0001f)
    }

    @Test
    fun `all materials and transparency survive persistence`() {
        UiMaterialStyle.entries.forEach { style ->
            val before = ThemeMaterialSetting(style, 0.55f)
            assertEquals(
                before,
                Json.decodeFromString<ThemeMaterialSetting>(Json.encodeToString(before)),
            )
        }
    }

    @Test
    fun `existing serialized material names remain readable`() {
        assertEquals(
            UiMaterialStyle.LIQUID_GLASS,
            Json.decodeFromString<UiMaterialStyle>("\"LIQUID_GLASS\""),
        )
        assertEquals(UiMaterialStyle.FROSTED, Json.decodeFromString<UiMaterialStyle>("\"FROSTED\""))
        assertEquals(
            UiMaterialStyle.ORIGINAL,
            Json.decodeFromString<UiMaterialStyle>("\"ORIGINAL\""),
        )
    }

    @Test
    fun `invalid imported transparency cannot reach renderer`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals(0.80f, ThemeMaterialSetting(fogTransparency = it).safeFogTransparency, 0f)
        }
        assertEquals(0f, ThemeMaterialSetting(fogTransparency = -1f).safeFogTransparency, 0f)
        assertEquals(1f, ThemeMaterialSetting(fogTransparency = 3f).safeFogTransparency, 0f)
    }
}
