package me.rerere.rikkahub.ui.components.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImageCropDeviceTest {
    @Test
    fun cropPreservesAlphaAndRotatesPixelsWithoutExceedingSizeLimit() {
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.TRANSPARENT)
        for (y in 0 until 40) for (x in 0 until 40) source.setPixel(x, y, Color.RED)
        val state = ImageCropState(source, source.asImageBitmap(), null)
        state.layout(Rect(0f, 0f, 800f, 800f), 1f)
        state.rotate()
        val cropped = state.crop(40)
        assertEquals(20, cropped.width)
        assertEquals(40, cropped.height)
        assertEquals(255, Color.alpha(cropped.getPixel(10, 5)))
        assertEquals(0, Color.alpha(cropped.getPixel(10, 35)))
        cropped.recycle()
        source.recycle()
    }

    @Test
    fun importedJpegHonorsExifOrientation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("crop-exif-", ".jpg", context.cacheDir)
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        for (y in 0 until 40) for (x in 0 until 40) source.setPixel(x, y, Color.RED)
        try {
            file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val image = loadCropImage(context, file.toUri(), 4096)
            assertNotNull(image)
            val bitmap = image!!.bitmap
            assertEquals(40, bitmap.width)
            assertEquals(80, bitmap.height)
            assertTrue(Color.red(bitmap.getPixel(20, 10)) > 200)
            assertTrue(Color.blue(bitmap.getPixel(20, 70)) > 200)
            bitmap.recycle()
        } finally {
            source.recycle()
            file.delete()
        }
    }
}
