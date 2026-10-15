package me.rerere.rikkahub.plugin.files

import java.io.File
import java.util.zip.ZipInputStream

internal fun requirePluginId(id: String): String {
    require(id.isNotBlank() && id != "." && id != ".." &&
        id.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) {
        "Invalid plugin ID"
    }
    return id
}

/** Canonical containment also rejects sibling-prefix paths and links pointing outside the root. */
internal fun resolvePluginFile(
    root: File,
    path: String,
    allowRoot: Boolean = false,
    allowAbsolute: Boolean = false,
): File {
    require(path.none { it == '\\' || it.isISOControl() }) { "Invalid plugin file path" }
    require(!Regex("^[A-Za-z]:").containsMatchIn(path)) { "Invalid plugin file path" }
    val input = File(path)
    require(allowAbsolute || !input.isAbsolute) { "Plugin file path must be relative" }
    val base = root.canonicalFile
    val target = (if (input.isAbsolute) input else File(base, path)).canonicalFile
    require(target.toPath().startsWith(base.toPath()) && (allowRoot || target != base)) {
        "Plugin file path is outside its directory"
    }
    return target
}

internal fun pluginDirectory(root: File, id: String): File {
    val name = requirePluginId(id)
    val directory = resolvePluginFile(root, name)
    require(directory == File(root.canonicalFile, name)) { "Plugin directory cannot alias another directory" }
    return directory
}

/** Both preview and installation use this before reading any manifest or executing plugin code. */
internal fun extractPluginZip(zipFile: File, destination: File) {
    check(destination.isDirectory || destination.mkdirs()) { "Cannot create plugin extraction directory" }
    ZipInputStream(zipFile.inputStream().buffered()).use { input ->
        var entry = input.nextEntry
        while (entry != null) {
            val target = resolvePluginFile(destination, entry.name, allowRoot = entry.isDirectory)
            if (entry.isDirectory) {
                check(target.isDirectory || target.mkdirs()) { "Cannot create plugin directory" }
            } else {
                val parent = requireNotNull(target.parentFile)
                check(parent.isDirectory || parent.mkdirs()) { "Cannot create plugin directory" }
                target.outputStream().use { input.copyTo(it) }
            }
            input.closeEntry()
            entry = input.nextEntry
        }
    }
}
