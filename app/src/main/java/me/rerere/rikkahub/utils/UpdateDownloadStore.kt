package me.rerere.rikkahub.utils

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class SavedUpdateDownload(
    val id: String,
    val info: UpdateInfo,
    val completed: Boolean = false,
    val cancelled: Boolean = false,
    val error: String? = null,
    val autoInstall: Boolean = true,
    val waitingForPermission: Boolean = false,
)

/**
 * Public release metadata only; completed and partial APKs survive cache cleanup and process death.
 */
internal class UpdateDownloadStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("app_update_download", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "app_updates")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableTask =
        MutableStateFlow(
            runCatching {
                preferences
                    .getString("task", null)
                    ?.let { json.decodeFromString<SavedUpdateDownload>(it) }
                    ?.also {
                        java.util.UUID.fromString(it.id)
                        validateUpdateDownload(it.info.download)
                    }
            }
                .getOrNull()
        )
    val task = mutableTask.asStateFlow()

    @Synchronized
    fun save(value: SavedUpdateDownload?) {
        val edit = preferences.edit()
        if (value == null) edit.remove("task")
        else edit.putString("task", json.encodeToString(value))
        check(edit.commit()) { "无法保存更新下载任务，请检查存储空间" }
        mutableTask.value = value
    }

    @Synchronized
    fun update(id: String, transform: (SavedUpdateDownload) -> SavedUpdateDownload) {
        val current = task.value?.takeIf { it.id == id } ?: return
        save(transform(current))
    }

    @Synchronized
    fun clear(id: String) {
        if (task.value?.id == id) save(null)
    }

    fun file(value: SavedUpdateDownload, partial: Boolean = false): File {
        validateUpdateDownload(value.info.download)
        val download = value.info.download
        val identity = "${download.url}|${download.sha256}|${download.bytes}"
        val key =
            MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        val directory = File(root, key)
        return File(directory, if (partial) "partial.apk" else download.name)
    }
}
