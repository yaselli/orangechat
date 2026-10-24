package me.rerere.rikkahub.data.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MediaCreationFilesDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun importedImagesRespectAllEightExifOrientations() {
        val dir = File(context.cacheDir, "media-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val expected = listOf(Color.RED, Color.GREEN, Color.YELLOW, Color.BLUE,
                Color.RED, Color.BLUE, Color.YELLOW, Color.GREEN)
            for (orientation in 1..8) {
                val source = File(dir, "source-$orientation.jpg")
                val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
                for (y in 0 until 40) for (x in 0 until 80) bitmap.setPixel(x, y, when {
                    y < 20 && x < 40 -> Color.RED
                    y < 20 -> Color.GREEN
                    x < 40 -> Color.BLUE
                    else -> Color.YELLOW
                })
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                bitmap.recycle()
                ExifInterface(source.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes()
                }
                val output = MediaCreationFiles.importImage(context, Uri.fromFile(source), dir)
                val result = BitmapFactory.decodeFile(output.absolutePath)
                assertEquals(if (orientation >= 5) 40 else 80, result.width)
                assertEquals(if (orientation >= 5) 80 else 40, result.height)
                val pixel = result.getPixel(5, 5)
                val color = expected[orientation - 1]
                assertTrue("EXIF $orientation", abs(Color.red(pixel) - Color.red(color)) < 40 &&
                    abs(Color.green(pixel) - Color.green(color)) < 40 && abs(Color.blue(pixel) - Color.blue(color)) < 40)
                result.recycle()
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun importsPreserveAlphaAndBoundTheDecodedImage() {
        val dir = File(context.cacheDir, "media-alpha-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(dir, "transparent.png")
            Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0x80112233.toInt())
                source.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
            val output = MediaCreationFiles.importImage(context, Uri.fromFile(source), dir)
            assertEquals("png", output.extension)
            BitmapFactory.decodeFile(output.absolutePath).apply {
                assertEquals(128, Color.alpha(getPixel(10, 10))); recycle()
            }
            val large = File(dir, "large.jpg")
            Bitmap.createBitmap(6000, 1000, Bitmap.Config.RGB_565).apply {
                eraseColor(Color.BLUE)
                large.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }
                recycle()
            }
            val resized = MediaCreationFiles.importImage(context, Uri.fromFile(large), dir)
            val info = MediaCreationFiles.imageInfo(resized)!!
            assertTrue(maxOf(info.width, info.height) <= 4096)
        } finally { dir.deleteRecursively() }
    }
}
