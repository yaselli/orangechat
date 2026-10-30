package me.rerere.rikkahub.ui.pages.mediacreation

import me.rerere.rikkahub.data.model.MediaCreationParams
import org.junit.Assert.*
import org.junit.Test

class MediaSketchAspectRatioTest {
    @Test
    fun usesPixelSizeBeforeRatioAndAllowsAnAutomaticPaper() {
        assertEquals(1.5f, MediaCreationParams(resolution = "1536x1024", aspectRatio = "1:1").sketchAspectRatio()!!, 0.001f)
        assertEquals(2f, MediaCreationParams(resolution = "2K", aspectRatio = " 16 : 8 ").sketchAspectRatio()!!, 0.001f)
        assertEquals(0.5f, MediaCreationParams(resolution = "512*1024").sketchAspectRatio()!!, 0.001f)
        assertNull(MediaCreationParams(resolution = "auto", aspectRatio = "adaptive").sketchAspectRatio())
    }

    @Test
    fun invalidSizesCannotMakeANonFiniteOrDegeneratePaper() {
        listOf("0:1", "-1:1", "1:0", "NaN:1", "Infinity:1", "10000:1", "1:2:3").forEach {
            assertNull(it, MediaCreationParams(aspectRatio = it).sketchAspectRatio())
        }
    }
}
