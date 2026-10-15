package me.rerere.rikkahub.plugin.files

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Unsafe path must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: validation fails before opening or changing the target file.
        }
    }

    @Test
    fun `normal files and nested data directories remain usable`() {
        val root = temporary.newFolder("plugin")
        val file = resolvePluginFile(root, "music/song.mp3")
        file.parentFile!!.mkdirs()
        file.writeText("music")
        assertEquals("music", resolvePluginFile(root, "music/song.mp3").readText())
        assertEquals(File(root, "music/song.mp3").canonicalFile, file)
        assertTrue(resolvePluginFile(root, "music/song.mp3").delete())
    }

    @Test
    fun `directory listing may use root but file operations may not`() {
        val root = temporary.newFolder("plugin")
        assertEquals(root.canonicalFile, resolvePluginFile(root, "", allowRoot = true))
        for (path in listOf("", ".", "nested/..")) rejected { resolvePluginFile(root, path) }
    }

    @Test
    fun `parent traversal and sibling name prefixes never escape root`() {
        val root = temporary.newFolder("plugin")
        val outside = temporary.newFolder("plugin-other")
        val sentinel = File(outside, "secret").apply { writeText("unchanged") }
        for (path in listOf("../plugin-other/secret", "nested/../../plugin-other/secret", "../../secret")) {
            rejected { resolvePluginFile(root, path).writeText("bad") }
        }
        assertEquals("unchanged", sentinel.readText())
    }

    @Test
    fun `data APIs accept their own returned absolute paths but reject external ones`() {
        val root = temporary.newFolder("plugin")
        val own = File(root, "image.png")
        assertEquals(own.canonicalFile, resolvePluginFile(root, own.absolutePath, allowAbsolute = true))
        rejected { resolvePluginFile(root, own.absolutePath) }
        rejected { resolvePluginFile(root, temporary.newFile("outside").absolutePath, allowAbsolute = true) }
    }

    @Test
    fun `windows paths backslash paths and controls are rejected`() {
        val root = temporary.newFolder("plugin")
        for (path in listOf("C:/outside", "C:\\outside", "..\\outside", "\\\\server\\file", "bad\u0000file")) {
            rejected { resolvePluginFile(root, path, allowAbsolute = true) }
        }
    }

    @Test
    fun `plugin ids are single directory names including normal unicode ids`() {
        for (id in listOf("com.example.music", "my-plugin_1", "音乐")) assertEquals(id, requirePluginId(id))
        for (id in listOf("", " ", ".", "..", "../other", "one/two", "one\\two", "/tmp", "C:plugin")) {
            rejected { requirePluginId(id) }
        }
    }

    @Test
    fun `file and directory symlinks cannot redirect data operations outside root`() {
        val root = temporary.newFolder("plugin")
        val outside = temporary.newFolder("outside")
        val secret = File(outside, "secret").apply { writeText("unchanged") }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "file-link").toPath(), secret.toPath())
        rejected { resolvePluginFile(root, "link/secret") }
        rejected { resolvePluginFile(root, "file-link") }
        assertEquals("unchanged", secret.readText())
    }

    @Test
    fun `plugin directory cannot be an alias to another plugins data directory`() {
        val root = temporary.newFolder("plugin_data")
        val second = File(root, "second").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "first").toPath(), second.toPath())
        rejected { pluginDirectory(root, "first") }
        assertEquals(second.canonicalFile, pluginDirectory(root, "second"))
    }

    @Test
    fun `normal archives support root markers wrapper directories and nested assets`() {
        val archive = archive("./" to "", "wrapped/" to "", "wrapped/manifest.json" to "{}",
            "wrapped/assets/index.html" to "page")
        val destination = temporary.newFolder("extracted")
        extractPluginZip(archive, destination)
        assertEquals("{}", File(destination, "wrapped/manifest.json").readText())
        assertEquals("page", File(destination, "wrapped/assets/index.html").readText())
    }

    @Test
    fun `zip traversal is rejected before overwriting a file outside extraction directory`() {
        val outside = temporary.newFile("sentinel").apply { writeText("unchanged") }
        val destination = temporary.newFolder("extracted")
        rejected { extractPluginZip(archive("../sentinel" to "bad"), destination) }
        assertEquals("unchanged", outside.readText())
    }

    @Test
    fun `absolute and windows archive paths are rejected`() {
        for ((index, name) in listOf("/outside", "C:/outside", "..\\outside").withIndex()) {
            rejected { extractPluginZip(archive(name to "bad"), temporary.newFolder("extracted-$index")) }
        }
    }

    @Test
    fun `extraction also checks preexisting symlink destinations`() {
        val destination = temporary.newFolder("extracted")
        val outside = temporary.newFolder("outside")
        Files.createSymbolicLink(File(destination, "linked").toPath(), outside.toPath())
        rejected { extractPluginZip(archive("linked/file" to "bad"), destination) }
        assertFalse(File(outside, "file").exists())
    }

    private fun archive(vararg entries: Pair<String, String>): File {
        val archive = temporary.newFile()
        ZipOutputStream(archive.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                if (!name.endsWith('/')) output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return archive
    }
}
