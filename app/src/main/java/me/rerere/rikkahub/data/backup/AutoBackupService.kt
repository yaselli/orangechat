package me.rerere.rikkahub.data.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import java.io.File
import java.time.Instant
import java.time.ZoneId

class AutoBackupService(
    private val store: AutoBackupStore,
    private val folder: SafBackupFolder,
    private val exporter: WebDavSync,
) {
    private val lock = Mutex()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    suspend fun backup(force: Boolean) = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext
        var archive: File? = null
        var candidate: String? = null
        try {
            _running.value = true
            val state = store.current()
            val config = state.config
            if (!force && !config.enabled) return@withContext
            check(config.folder.isNotBlank()) { "请先选择备份文件夹" }
            folder.requireAccess(config.folder)
            cleanup()
            check(store.current().retiredFiles.isEmpty()) { "旧备份尚未清理，请检查文件夹权限后重试" }
            if (!force && state.lastSuccess > 0 && Instant.now().isBefore(
                nextAutoBackupAt(Instant.now(), state.lastSuccess, config, ZoneId.systemDefault())
            )) return@withContext
            val prepared = exporter.prepareBackupFile(WebDavConfig(), requireCompletePlugins = true)
            archive = prepared
            val job = currentCoroutineContext()
            VerifiedBackupWriter.validateArchive(prepared) { job.ensureActive() }
            // Never overwrite the known-good file. Persist a recovery pointer before writing the candidate.
            val uri = folder.create(config.folder)
            candidate = uri
            store.pending(uri)
            VerifiedBackupWriter.copyAndVerify(
                prepared, { folder.output(uri) }, { folder.input(uri) },
            ) { job.ensureActive() }
            store.publish(uri, config.revision, System.currentTimeMillis())
            cleanup()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            store.error(when (error) {
                is SecurityException -> "文件夹授权失效，请重新选择文件夹"
                else -> error.message?.take(160) ?: "备份失败，原备份已保留"
            })
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    candidate?.let { uri ->
                        if (store.current().latestFile != uri) {
                            runCatching { folder.delete(uri); store.clearPending() }
                        }
                    }
                } finally {
                    archive?.delete()
                    _running.value = false
                    lock.unlock()
                }
            }
        }
    }

    private suspend fun cleanup() {
        val state = store.current()
        if (state.pendingFile.isNotBlank() && state.pendingFile != state.latestFile) {
            folder.delete(state.pendingFile)
            store.clearPending()
        }
        for (uri in state.retiredFiles) {
            if (uri == state.latestFile) continue
            try {
                folder.delete(uri)
                store.retiredRemoved(uri)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                store.error("新备份已保存，但旧备份未能清理；下次将重试")
            }
        }
        if (state.retiredFiles.isNotEmpty() && store.current().retiredFiles.isEmpty()) store.error("")
    }
}
