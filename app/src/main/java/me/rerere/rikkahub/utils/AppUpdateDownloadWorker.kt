package me.rerere.rikkahub.utils

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.service.ServiceNotificationIds
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext

class AppUpdateDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    private val downloads
        get() = GlobalContext.get().get<AppUpdateDownloader>()

    override suspend fun doWork(): Result =
        downloads.transferLock.withLock {
            val task =
                downloads.store.task.value?.takeIf { it.id == id.toString() && !it.cancelled }
                    ?: return@withLock Result.failure()
            val partial = downloads.store.file(task, true)
            val complete = downloads.store.file(task)
            try {
                downloads.store.update(task.id) { it.copy(error = null) }
                applicationContext
                    .getSystemService(NotificationManager::class.java)
                    .cancel(ServiceNotificationIds.APP_UPDATE_RESULT)
                setForeground(getForegroundInfo())
                complete.parentFile?.mkdirs()
                if (complete.isFile) {
                    try {
                        verifyUpdateApk(applicationContext, complete, task.info)
                    } catch (_: InvalidUpdateException) {
                        complete.delete()
                    }
                }
                if (!complete.isFile) {
                    var lastBytes = partial.length()
                    var lastTime = System.nanoTime()
                    transferAppUpdate(
                        GlobalContext.get().get<OkHttpClient>(),
                        task.info.download,
                        partial,
                    ) { bytes, total ->
                        val now = System.nanoTime()
                        val speed =
                            ((bytes - lastBytes).coerceAtLeast(0) * 1_000_000_000.0 /
                                    (now - lastTime).coerceAtLeast(1))
                                .toLong()
                        lastBytes = bytes
                        lastTime = now
                        setProgress(
                            workDataOf("received" to bytes, "total" to total, "speed" to speed)
                        )
                        setForeground(foreground("正在下载 ${task.info.version}", bytes, total))
                    }
                    setProgress(workDataOf("verifying" to true))
                    setForeground(
                        foreground("正在检查安装包", task.info.download.bytes, task.info.download.bytes)
                    )
                    verifyUpdateApk(applicationContext, partial, task.info)
                    currentCoroutineContext().ensureActive()
                    check(
                        downloads.store.task.value?.let { it.id == task.id && !it.cancelled } ==
                            true
                    )
                    check(partial.renameTo(complete)) { "无法保存安装包，请检查存储空间" }
                }
                currentCoroutineContext().ensureActive()
                downloads.store.update(task.id) { it.copy(completed = true, error = null) }
                notifyResult("更新已下载，点击安装 ${task.info.version}")
                Result.success()
            } catch (cancelled: CancellationException) {
                throw cancelled // Keep the partial file and WorkManager task for an
                                // explicit/automatic resume.
            } catch (error: Exception) {
                if (error is InvalidUpdateException) {
                    partial.delete()
                    complete.delete()
                }
                val message = error.message ?: "更新下载失败，可重试"
                downloads.store.update(task.id) { it.copy(error = message, completed = false) }
                if (error is IOException && runAttemptCount < 2) Result.retry()
                else {
                    notifyResult("下载未完成，点击查看并重试")
                    Result.failure(workDataOf("error" to message))
                }
            }
        }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("正在准备下载更新", 0, 0)

    private fun foreground(text: String, bytes: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "应用更新下载", NotificationManager.IMPORTANCE_LOW)
        )
        val notification =
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("西瓜瓣更新")
                .setContentText(text)
                .setContentIntent(openDownload())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setProgress(
                    100,
                    if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0,
                    total <= 0,
                )
                .addAction(
                    0,
                    "暂停",
                    WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
                )
                .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(
                ServiceNotificationIds.APP_UPDATE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        else ForegroundInfo(ServiceNotificationIds.APP_UPDATE, notification)
    }

    private fun openDownload(): PendingIntent =
        PendingIntent.getActivity(
            applicationContext,
            50001,
            Intent(applicationContext, RouteActivity::class.java)
                .putExtra("openUpdateDownload", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    @SuppressLint("MissingPermission")
    private fun notifyResult(text: String) {
        // The in-app record remains available even when notification permission is declined.
        runCatching {
            applicationContext
                .getSystemService(NotificationManager::class.java)
                .notify(
                    ServiceNotificationIds.APP_UPDATE_RESULT,
                    NotificationCompat.Builder(applicationContext, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("西瓜瓣更新")
                        .setContentText(text)
                        .setContentIntent(openDownload())
                        .setAutoCancel(true)
                        .setSilent(true)
                        .build(),
                )
        }
    }

    private companion object {
        const val CHANNEL = "app_update_download"
    }
}
