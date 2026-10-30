package me.rerere.rikkahub.data.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = GlobalContext.get().get<AutoBackupStore>()
        val manual = inputData.getBoolean("manual", false)
        if (!manual && !store.current().config.enabled) return Result.success()
        return try {
            setForeground(getForegroundInfo())
            GlobalContext.get().get<AutoBackupService>().backup(manual)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (store.current().error.isBlank()) store.error("系统暂未允许后台备份，请打开应用后重试")
            // Avoid endless retries for a missing grant; the UI keeps the failure visible.
            if (runAttemptCount < 2 && !manual) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "自动备份", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("西瓜瓣正在备份")
            .setContentText("正在保存并校验备份，请勿移除存储设备")
            .setOngoing(true).setSilent(true).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(4381, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(4381, notification)
    }

    private companion object {
        const val CHANNEL = "auto_backup"
    }
}
