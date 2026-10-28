package me.rerere.rikkahub.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig

enum class UpdateDownloadPhase {
    Idle,
    Waiting,
    Downloading,
    Verifying,
    Ready,
    Paused,
    Failed,
}

data class UpdateDownloadState(
    val info: UpdateInfo? = null,
    val phase: UpdateDownloadPhase = UpdateDownloadPhase.Idle,
    val received: Long = 0,
    val total: Long = 0,
    val bytesPerSecond: Long = 0,
    val error: String? = null,
    val autoInstall: Boolean = false,
    val waitingForPermission: Boolean = false,
)

/** One persistent WorkManager download, shared by automatic prompts, About and notifications. */
class AppUpdateDownloader(private val context: Context, private val scope: AppScope) {
    internal val store = UpdateDownloadStore(context)
    internal val transferLock = Mutex()
    private val work = WorkManager.getInstance(context)
    private val installing = MutableStateFlow(false)
    private val installError = MutableStateFlow<String?>(null)
    private var installJob: Job? = null
    private var submitting = false

    private val downloadState =
        store.task.flatMapLatest { task ->
            if (task == null) flowOf(UpdateDownloadState())
            else
                work.getWorkInfoByIdFlow(UUID.fromString(task.id)).map { info ->
                    stateOf(task, info)
                }
        }
    val state =
        combine(downloadState, installing, installError) { download, checking, error ->
                download.copy(
                    phase = if (checking) UpdateDownloadPhase.Verifying else download.phase,
                    error = error ?: download.error,
                )
            }
            .stateIn(scope, SharingStarted.Eagerly, UpdateDownloadState())

    init {
        store.task.value
            ?.takeIf { it.info.versionCode <= BuildConfig.VERSION_CODE.toInt() }
            ?.let { old ->
                scope.launch(Dispatchers.IO) {
                    work.cancelWorkById(UUID.fromString(old.id))
                    if (store.task.value?.id == old.id) {
                        store.file(old).parentFile?.deleteRecursively()
                        store.clear(old.id)
                    }
                }
            }
    }

    private fun stateOf(task: SavedUpdateDownload, info: WorkInfo?): UpdateDownloadState {
        val complete = store.file(task).isFile
        val phase =
            when {
                task.cancelled || info?.state == WorkInfo.State.CANCELLED ->
                    UpdateDownloadPhase.Paused
                task.completed &&
                    complete &&
                    (info == null || info.state == WorkInfo.State.SUCCEEDED) ->
                    UpdateDownloadPhase.Ready
                info?.state == WorkInfo.State.RUNNING ->
                    if (info.progress.getBoolean("verifying", false)) UpdateDownloadPhase.Verifying
                    else UpdateDownloadPhase.Downloading
                info?.state in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) ->
                    UpdateDownloadPhase.Waiting
                info?.state == WorkInfo.State.FAILED || task.error != null ->
                    UpdateDownloadPhase.Failed
                else -> UpdateDownloadPhase.Paused
            }
        val bytes = if (complete) task.info.download.bytes else store.file(task, true).length()
        return UpdateDownloadState(
            task.info,
            phase,
            bytes,
            task.info.download.bytes,
            info?.progress?.getLong("speed", 0) ?: 0,
            task.error ?: info?.outputData?.getString("error"),
            task.autoInstall,
            task.waitingForPermission,
        )
    }

    fun start(info: UpdateInfo) {
        if (
            submitting ||
                installing.value ||
                state.value.phase in
                    listOf(
                        UpdateDownloadPhase.Downloading,
                        UpdateDownloadPhase.Waiting,
                        UpdateDownloadPhase.Verifying,
                    )
        )
            return
        submitting = true
        installError.value = null
        scope.launch {
            try {
                validateUpdateDownload(info.download)
                val request =
                    OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>()
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                withContext(Dispatchers.IO) {
                    store.save(SavedUpdateDownload(request.id.toString(), info))
                    work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request).result.get()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                installError.value = error.message ?: "无法开始下载，请重试"
            } finally {
                submitting = false
            }
        }
    }

    fun cancel() {
        installJob?.cancel()
        val task = store.task.value ?: return
        scope.launch(Dispatchers.IO) {
            store.update(task.id) { it.copy(cancelled = true, autoInstall = false) }
            work.cancelWorkById(UUID.fromString(task.id))
        }
    }

    fun install(context: Context) {
        val task = store.task.value?.takeIf { it.completed && !it.cancelled } ?: return
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            installing.value = true
            installError.value = null
            try {
                withContext(Dispatchers.IO) {
                    store.update(task.id) { it.copy(autoInstall = false) }
                    verifyUpdateApk(context.applicationContext, store.file(task), task.info)
                }
                currentCoroutineContext().ensureActive()
                if (store.task.value?.let { it.id == task.id && !it.cancelled } != true)
                    return@launch
                if (!context.packageManager.canRequestPackageInstalls()) {
                    withContext(Dispatchers.IO) {
                        store.update(task.id) { it.copy(waitingForPermission = true) }
                    }
                    context.startActivity(
                        Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } else {
                    withContext(Dispatchers.IO) {
                        store.update(task.id) { it.copy(waitingForPermission = false) }
                    }
                    val uri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            store.file(task),
                        )
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                            )
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                installError.value = error.message ?: "无法打开安装页面，请重试"
                if (error is InvalidUpdateException)
                    withContext(Dispatchers.IO) {
                        store.file(task).delete()
                        store.update(task.id) {
                            it.copy(completed = false, error = error.message, autoInstall = false)
                        }
                    }
                Toast.makeText(context, installError.value, Toast.LENGTH_LONG).show()
            } finally {
                installing.value = false
            }
        }
    }

    fun resumeInstall(context: Context) {
        if (
            store.task.value?.waitingForPermission == true &&
                context.packageManager.canRequestPackageInstalls()
        )
            install(context)
    }

    companion object {
        internal const val WORK_NAME = "app_update_download"
    }
}
