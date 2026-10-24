package me.rerere.rikkahub.data.files

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Include completed media and drafts with FILES backups; never follow links outside this owned directory. */
internal object MediaCreationArchive {
    private const val PREFIX = "media_creation/"

    fun write(filesDir: File, output: ZipOutputStream) {
        val root = File(filesDir, "media_creation").canonicalFile
        require(root.parentFile == filesDir.canonicalFile) { "Invalid media directory" }
        if (!root.isDirectory) return
        root.walkTopDown().onEnter {
            !Files.isSymbolicLink(it.toPath()) && (it.canonicalFile.path.startsWith(root.path + File.separator) || it == root)
        }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) &&
                it.canonicalFile.path.startsWith(root.path + File.separator) && !it.name.endsWith(".part") }
            .forEach { file ->
                output.putNextEntry(ZipEntry(PREFIX + file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
    }

    fun restore(filesDir: File, name: String, input: InputStream) {
        require(name.startsWith(PREFIX)) { "Invalid media archive entry" }
        val relative = name.removePrefix(PREFIX)
        require(relative.isNotBlank() && !relative.endsWith(".part") && '\\' !in relative &&
            relative.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid media archive path" }
        val root = File(filesDir, "media_creation").canonicalFile
        require(root.parentFile == filesDir.canonicalFile) { "Invalid media directory" }
        val target = File(root, relative).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "Media archive path escaped its directory" }
        target.parentFile?.mkdirs()
        target.outputStream().use { input.copyTo(it) }
    }
}
