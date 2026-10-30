package me.rerere.rikkahub.utils

import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateDownloadDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun taskAndPartialFileSurviveStoreRecreationAndCancellation() {
        val store = UpdateDownloadStore(context)
        val previous = store.task.value
        val name = "Xiguaban-99.99.99-2000000000-universal-debug.apk"
        val task =
            SavedUpdateDownload(
                UUID.randomUUID().toString(),
                UpdateInfo(
                    "99.99.99-debug.1",
                    2000000000,
                    "Test only",
                    UpdateDownload(name, "$UPDATE_RELEASES_URL/download/test/$name", "1 MB", 100),
                    "unused",
                ),
            )
        val partial = store.file(task, true)
        try {
            store.save(task)
            partial.parentFile!!.mkdirs()
            partial.writeBytes(byteArrayOf(1, 2, 3))
            val recreated = UpdateDownloadStore(context)
            assertEquals(task, recreated.task.value)
            assertArrayEquals(
                byteArrayOf(1, 2, 3),
                recreated.file(recreated.task.value!!, true).readBytes(),
            )
            recreated.update(task.id) { it.copy(cancelled = true, autoInstall = false) }
            assertTrue(UpdateDownloadStore(context).task.value!!.cancelled)
            assertEquals(3L, partial.length())
            val uri =
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", partial)
            assertEquals("content", uri.scheme)
            context.contentResolver.openInputStream(uri)!!.use { assertEquals(1, it.read()) }
        } finally {
            store.save(previous)
            partial.parentFile?.deleteRecursively()
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun installedApkAndArchiveExposeTheSamePackageChannelAndSigningIdentity() {
        val manager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = updateApkIdentity(manager.getPackageInfo(context.packageName, flags))
        val archive = updateApkArchiveIdentity(context, File(context.applicationInfo.sourceDir))
        assertEquals(installed, archive)
        assertEquals(context.packageName, installed.packageName)
        assertTrue(installed.signers.isNotEmpty())
    }

    @Test
    fun unreadableArchiveCannotSupplyAChannelIdentity() {
        val file = File(context.cacheDir, "invalid-update-${UUID.randomUUID()}.apk")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertThrows(InvalidUpdateException::class.java) {
                updateApkArchiveIdentity(context, file)
            }
        } finally {
            file.delete()
        }
    }
}
