package me.rerere.rikkahub.data.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class AutoBackupScheduler(private val context: Context) {
    fun schedule(config: AutoBackupConfig, changed: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!config.enabled || config.folder.isBlank()) {
            manager.cancelUniqueWork(PERIODIC)
            return
        }
        val now = Instant.now()
        val delay = Duration.between(now, nextAutoBackupAt(now, 0, config, ZoneId.systemDefault())).toMillis()
        // Daily wake-up checks the calendar interval; manual backups reset the due date too.
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(
            PERIODIC,
            if (changed) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun backupNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoBackupWorker>().setInputData(workDataOf("manual" to true)).build(),
        )
    }

    private companion object {
        const val PERIODIC = "xiguaban_auto_backup"
        const val MANUAL = "xiguaban_manual_auto_backup"
    }
}
