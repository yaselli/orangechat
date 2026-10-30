package me.rerere.rikkahub.ui.components.sketch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SketchDeviceTest {
    @Test
    fun textSelectionTransformAndHistoryPreserveTheOriginalItem() {
        val state = SketchState(1f).apply { fit(Size(400f, 300f)) }
        assertEquals(Size(300f, 300f), state.paperSize)
        state.beginText(Offset.Zero, 6f)
        state.write("草图 Cat")
        val item = state.steps.single() as SketchStep.Text
        state.select(item.bounds, topmost = true)
        assertEquals(listOf(item), state.selection)
        state.moving = SketchPlacement(offset = Offset(30f, 20f), scale = 1.5f)
        state.settle()
        assertEquals(Offset(30f, 20f), state.placements().getValue(item).offset)
        state.undo()
        assertEquals(SketchPlacement.None, state.placements().getValue(item))
        state.redo()
        assertEquals(1.5f, state.placements().getValue(item).scale, 0.001f)
        state.clear()
        assertTrue(state.isBlank)
        state.undo()
        assertFalse(state.isBlank)
        state.undo()
        state.beginText(Offset(20f, 10f), 4f)
        state.write("new")
        assertFalse(state.canRedo)
        assertEquals("草图 Cat", item.text)
    }

    @Test
    fun blankPaperExportsPngAtADeviceIndependentSizeWithVisibleText() {
        val state = SketchState(2f).apply {
            fit(Size(400f, 400f))
            beginText(Offset(-80f, 0f), 8f)
            write("Sketch")
        }
        val result = state.toResult()
        try {
            assertEquals("png", result.extension)
            assertEquals("image/png", result.mimeType)
            assertEquals(1536, result.bitmap.width)
            assertEquals(768, result.bitmap.height)
            val bytes = result.encode()
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                val pixels = IntArray(decoded.width * decoded.height)
                decoded.getPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
                assertTrue(pixels.any { AndroidColor.red(it) < 100 && AndroidColor.alpha(it) == 255 })
                assertEquals(AndroidColor.WHITE, decoded.getPixel(0, 0))
            } finally { decoded.recycle() }
        } finally { result.bitmap.recycle() }
    }

    @Test
    fun eraserAndClearPreserveTheReferencePhoto() {
        val background = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888).apply {
            eraseColor(AndroidColor.BLUE)
        }
        val state = SketchState().apply {
            fit(Size(400f, 400f))
            useBackground(background.asImageBitmap())
            beginText(Offset.Zero, 5f)
            write("annotation")
            val erase = erase(Offset.Zero, 1000f)
            finish(erase)
        }
        try {
            val erased = state.toResult()
            try {
                assertEquals("jpg", erased.extension)
                assertEquals(640, erased.bitmap.width)
                assertEquals(320, erased.bitmap.height)
                assertEquals(AndroidColor.BLUE, erased.bitmap.getPixel(320, 160))
            } finally { erased.bitmap.recycle() }
            state.clear()
            val cleared = state.toResult()
            try { assertEquals(AndroidColor.BLUE, cleared.bitmap.getPixel(320, 160)) }
            finally { cleared.bitmap.recycle() }
            assertEquals(AndroidColor.BLUE, background.getPixel(320, 160))
        } finally { background.recycle() }
    }
}
