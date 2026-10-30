package me.rerere.rikkahub.ui.theme

import me.rerere.rikkahub.data.datastore.ThemeMaterialSetting
import me.rerere.rikkahub.data.datastore.UiMaterialStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeMaterialProfileTest {
    @Test
    fun `increasing fog transparency reduces tint without blurring text more`() {
        for (dark in listOf(false, true)) {
            for (role in listOf(MaterialRole.BUBBLE, MaterialRole.INPUT)) {
                val low = ThemeMaterialSetting(fogTransparency = 0.3f).profile(dark, role)
                val high = ThemeMaterialSetting(fogTransparency = 0.8f).profile(dark, role)
                assertTrue(high.tintAlpha < low.tintAlpha)
                assertEquals(low.blurRadius, high.blurRadius, 0f)
            }
        }
    }

    @Test
    fun `three materials have distinct optics and matte grain stays subtle`() {
        val fog = ThemeMaterialSetting().profile(false, MaterialRole.BUBBLE)
        val matte =
            ThemeMaterialSetting(UiMaterialStyle.FROSTED).profile(false, MaterialRole.BUBBLE)
        val water =
            ThemeMaterialSetting(UiMaterialStyle.LIQUID_GLASS).profile(false, MaterialRole.BUBBLE)
        assertTrue(water.blurRadius < fog.blurRadius)
        assertTrue(fog.blurRadius < matte.blurRadius)
        assertTrue(fog.tintAlpha < matte.tintAlpha)
        assertTrue(water.edgeAlpha > fog.edgeAlpha)
        assertTrue(matte.edgeAlpha < fog.edgeAlpha)
        assertTrue(matte.noise in 0f..0.02f)
    }

    @Test
    fun `modal readability and numeric bounds survive every style mode and imported opacity`() {
        for (style in UiMaterialStyle.entries) {
            for (dark in listOf(false, true)) {
                for (opacity in listOf(-4f, 0f, 0.8f, 1f, 4f, Float.NaN)) {
                    val material = ThemeMaterialSetting(style, opacity)
                    for (role in MaterialRole.entries) {
                        val p = material.profile(dark, role)
                        assertTrue(p.tintAlpha in 0f..1f)
                        assertTrue(p.edgeAlpha in 0f..1f)
                        assertTrue(p.highlightAlpha in 0f..1f)
                        assertTrue(p.noise in 0f..1f)
                        assertTrue(p.blurRadius.isFinite() && p.blurRadius >= 0f)
                    }
                    val modal = material.profile(dark, MaterialRole.DIALOG)
                    assertTrue(modal.tintAlpha >= 0.94f)
                    assertEquals(0f, modal.blurRadius, 0f)
                }
            }
        }
    }

    @Test
    fun `original appearance requests no optical processing`() {
        for (role in MaterialRole.entries) {
            val p = ThemeMaterialSetting(UiMaterialStyle.ORIGINAL).profile(false, role)
            assertEquals(1f, p.tintAlpha, 0f)
            assertEquals(0f, p.blurRadius, 0f)
            assertEquals(0f, p.noise, 0f)
            assertEquals(0f, p.edgeAlpha, 0f)
        }
    }
}
