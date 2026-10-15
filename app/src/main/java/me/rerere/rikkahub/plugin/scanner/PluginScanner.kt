/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.scanner

import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.security.SecurityAuditRepository
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import java.io.File
import java.io.FileOutputStream
import me.rerere.rikkahub.plugin.files.extractPluginZip
import me.rerere.rikkahub.plugin.files.pluginDirectory
import me.rerere.rikkahub.plugin.files.requirePluginId
import me.rerere.rikkahub.plugin.files.resolvePluginFile
import java.nio.file.Files

/**
 * 插件扫描器
 * 负责扫描、导入和管理插件目录
 */
class PluginScanner(
    private val context: Context,
    private val auditRepo: SecurityAuditRepository? = null,
) {
    companion object {
        const val PLUGINS_DIR = "Orangechat/plugins"
        const val MANIFEST_FILE = "manifest.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 获取插件根目录
     * 使用内部存储根目录 /storage/emulated/0/Orangechat/plugins/
     */
    val pluginsDir: File
        get() = File(Environment.getExternalStorageDirectory(), PLUGINS_DIR).apply { mkdirs() }

    /**
     * 确保插件目录存在
     */
    fun ensurePluginsDir(): File = pluginsDir

    /**
     * 扫描所有插件
     */
    fun scanPlugins(): List<PluginInfo> {
        val dir = ensurePluginsDir()
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }

        return dir.listFiles { file -> file.isDirectory }
            ?.mapNotNull { pluginDir -> loadPluginInfo(pluginDir) }
            ?: emptyList()
    }

    /**
     * 加载单个插件信息
     */
    fun loadPluginInfo(pluginDir: File): PluginInfo? {
        val manifestFile = File(pluginDir, MANIFEST_FILE)
        if (!manifestFile.exists()) {
            return null
        }

        return try {
            resolvePluginFile(pluginsDir, pluginDir.name)
            val content = resolvePluginFile(pluginDir, MANIFEST_FILE).readText()
            val manifest = json.decodeFromString(PluginManifest.serializer(), content)
            validateManifestPaths(manifest, pluginDir)

            // 完整性校验：若存在 .integrity 文件则验证，失败则禁用并标记错误
            val integrityFile = resolvePluginFile(pluginDir, ".integrity")
            if (integrityFile.exists()) {
                val stored = integrityFile.readText().trim()
                val current = computePluginChecksum(pluginDir)
                if (stored != current) {
                    return PluginInfo(
                        manifest = manifest,
                        directory = pluginDir,
                        isEnabled = false,
                        loadError = "完整性校验失败：插件文件已被篡改或损坏"
                    )
                }
            }

            PluginInfo(
                manifest = manifest,
                directory = pluginDir,
                isEnabled = true // 默认启用
            )
        } catch (e: Exception) {
            // 解析失败，返回错误状态的插件
            PluginInfo(
                manifest = PluginManifest(
                    id = pluginDir.name,
                    name = pluginDir.name,
                    description = "加载失败: ${e.message}",
                    version = "error",
                    author = "unknown",
                    icon = "⚠️",
                    entry = "",
                    tools = emptyList(),
                    config = emptyList()
                ),
                directory = pluginDir,
                isEnabled = false,
                loadError = e.message
            )
        }
    }

    /**
     * 从ZIP文件预览插件（解压到独立缓存目录后解析 manifest，不安装或执行）
     * 返回 manifest 和临时目录，供调用方展示权限确认对话框。
     * 调用方确认后应调用 [completeImport] 完成导入；取消时应清理临时目录。
     */
    suspend fun previewFromZip(uri: Uri): Result<Pair<PluginManifest, File>> {
        var tempFile: File? = null
        var tempDir: File? = null
        var keepDirectory = false
        return try {
            val archive = File.createTempFile("plugin_preview_", ".zip", context.cacheDir)
            tempFile = archive
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(archive).use { output -> input.copyTo(output) }
            } ?: error("无法读取文件")

            val directory = Files.createTempDirectory(context.cacheDir.toPath(), "plugin_preview_").toFile()
            tempDir = directory
            extractPluginZip(archive, directory)
            val manifestFile = findManifest(directory) ?: error("找不到 manifest.json")
            val manifest = json.decodeFromString(PluginManifest.serializer(), manifestFile.readText())
            validateManifestPaths(manifest, requireNotNull(manifestFile.parentFile))
            keepDirectory = true
            Result.success(manifest to directory)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempFile?.delete()
            if (!keepDirectory) tempDir?.deleteRecursively()
        }
    }

    /**
     * 完成插件导入（在 previewFromZip 后调用）
     * @param manifest 预览阶段解析的 manifest
     * @param tempDir 预览阶段解压的临时目录
     */
    suspend fun completeImport(manifest: PluginManifest, tempDir: File): Result<PluginInfo> {
        return try {
            val existingPlugins = scanPlugins()
            if (existingPlugins.any { it.manifest.id == manifest.id }) {
                tempDir.deleteRecursively()
                return Result.failure(IllegalArgumentException("插件 ${manifest.id} 已存在"))
            }

            val manifestFile = findManifest(tempDir)
                ?: run {
                    tempDir.deleteRecursively()
                    return Result.failure(IllegalArgumentException("找不到 manifest.json"))
                }

            val sourceDir = requireNotNull(manifestFile.parentFile)
            validateManifestPaths(manifest, sourceDir)
            val entryFile = resolvePluginFile(sourceDir, manifest.entry)
            if (!entryFile.isFile) {
                tempDir.deleteRecursively()
                return Result.failure(IllegalArgumentException("找不到入口文件: ${manifest.entry}"))
            }

            val pluginDir = pluginDirectory(pluginsDir, manifest.id)
            require(!pluginDir.exists()) { "插件目录 ${manifest.id} 已存在" }
            sourceDir.copyRecursively(pluginDir, overwrite = true)
            tempDir.deleteRecursively()

            // 写入完整性校验和
            runCatching {
                val checksum = computePluginChecksum(pluginDir)
                File(pluginDir, ".integrity").writeText(checksum)
            }

            loadPluginInfo(pluginDir)?.let {
                auditRepo?.log(
                    category = "plugin",
                    action = "installed",
                    target = manifest.id,
                    detail = "插件 ${manifest.name} (${manifest.id}) v${manifest.version} 已安装，作者: ${manifest.author}",
                    status = "success",
                )
                Result.success(it)
            } ?: Result.failure(IllegalStateException("无法加载插件信息"))
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            Result.failure(e)
        }
    }

    /**
     * 从ZIP文件导入插件（旧版一次性导入，保留用于兼容）
     */
    suspend fun importFromZip(uri: Uri): Result<PluginInfo> {
        val preview = previewFromZip(uri)
        val (manifest, directory) = preview.getOrElse { return Result.failure(it) }
        return completeImport(manifest, directory)
    }

    /**
     * 删除插件
     */
    fun deletePlugin(pluginId: String): Boolean {
        return runCatching {
            val pluginDir = pluginDirectory(pluginsDir, pluginId)
            pluginDir.exists() && pluginDir.deleteRecursively()
        }.getOrDefault(false)
    }

    /**
     * 获取插件目录
     */
    fun getPluginDir(pluginId: String): File {
        return pluginDirectory(pluginsDir, pluginId)
    }

    private fun validateManifestPaths(manifest: PluginManifest, directory: File) {
        requirePluginId(manifest.id)
        resolvePluginFile(directory, manifest.entry)
        manifest.customPageWebView?.let { resolvePluginFile(directory, it.entry) }
    }

    /**
     * 查找manifest.json文件
     * 优先在根目录查找，然后在子目录中查找
     */
    private fun findManifest(dir: File): File? {
        // 优先在根目录找
        val rootManifest = resolvePluginFile(dir, MANIFEST_FILE)
        if (rootManifest.exists()) {
            return rootManifest
        }

        // 在子目录中查找
        return dir.listFiles { file -> file.isDirectory }
            ?.asSequence()
            ?.map { subdir -> resolvePluginFile(dir, "${subdir.name}/$MANIFEST_FILE") }
            ?.firstOrNull { it.exists() }
    }

    /**
     * 计算插件目录的 SHA-256 校验和（排除 .integrity 文件本身，稳定排序）
     */
    private fun computePluginChecksum(dir: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        dir.walkTopDown()
            .onEnter { directory ->
                resolvePluginFile(dir, directory.relativeTo(dir).path, allowRoot = true)
                true
            }
            .onEach { file -> resolvePluginFile(dir, file.relativeTo(dir).path, allowRoot = true) }
            .filter { it.isFile && it.name != ".integrity" }
            .sortedBy { it.relativeTo(dir).path.replace('\\', '/') }
            .forEach { file ->
                digest.update(file.relativeTo(dir).path.replace('\\', '/').toByteArray(Charsets.UTF_8))
                digest.update(file.readBytes())
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
