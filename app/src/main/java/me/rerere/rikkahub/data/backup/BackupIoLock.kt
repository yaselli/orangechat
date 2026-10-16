package me.rerere.rikkahub.data.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Restore writes database files directly; it must not overlap snapshot creation. */
internal object BackupIoLock {
    private val mutex = Mutex()
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
