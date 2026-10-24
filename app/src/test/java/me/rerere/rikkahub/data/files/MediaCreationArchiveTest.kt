package me.rerere.rikkahub.data.files

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MediaCreationArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun mediaDraftsAndOutputsRoundTripWithoutIncompleteDownloads() {
        val source = folder.newFolder("source")
        File(source, "media_creation/session/record/out.mp4").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0, 1, -1)) }
        File(source, "media_creation/session/draft/reference.png").apply { parentFile.mkdirs(); writeText("draft") }
        File(source, "media_creation/session/record/out.mp4.part").writeText("partial")
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { MediaCreationArchive.write(source, it) }
        }.toByteArray()
        val target = folder.newFolder("target")
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                MediaCreationArchive.restore(target, entry.name, input)
            }
        }
        assertArrayEquals(byteArrayOf(0, 1, -1), File(target, "media_creation/session/record/out.mp4").readBytes())
        assertEquals("draft", File(target, "media_creation/session/draft/reference.png").readText())
        assertFalse(File(target, "media_creation/session/record/out.mp4.part").exists())
    }

    @Test
    fun archiveDoesNotFollowExternalFilesOrDirectoryLoops() {
        val source = folder.newFolder("links")
        val root = File(source, "media_creation").apply { mkdirs() }
        val outside = folder.newFile("outside.txt").apply { writeText("private") }
        java.nio.file.Files.createSymbolicLink(File(root, "external").toPath(), outside.toPath())
        java.nio.file.Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { MediaCreationArchive.write(source, it) }
        }.toByteArray()
        ZipInputStream(ByteArrayInputStream(bytes)).use { assertNull(it.nextEntry) }
    }

    @Test
    fun restoreRejectsTraversalOtherFoldersAndPartialFiles() {
        listOf("media_creation/../private", "media_creation//absolute", "media_creation/./private",
            "media_creation/a\\..\\private", "upload/private", "media_creation/a/out.part").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                MediaCreationArchive.restore(folder.root, name, ByteArrayInputStream(byteArrayOf(1)))
            }
        }
        assertFalse(File(folder.root, "private").exists())
    }
}
