package me.rerere.rikkahub.ui.components.ai

import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.audio.recordRawAudio
import me.rerere.rikkahub.data.files.FilesManager
import org.koin.compose.koinInject
import java.io.File

@Composable
internal fun RawAudioRecordingDialog(onRecorded: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val files: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var stop by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    var recording by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }

    fun cancel() {
        job?.cancel()
        onDismiss()
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) cancel()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            job?.cancel()
        }
    }
    LaunchedEffect(recording) {
        if (recording) {
            val started = SystemClock.elapsedRealtime()
            while (true) {
                elapsed = (SystemClock.elapsedRealtime() - started) / 1000
                delay(200)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { cancel() },
        title = { Text("录音发给模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (recording) "正在录音：${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')}"
                    else "录音会作为原始音频附件发送，模型可以听到声音。最长 2 分钟。")
                Text("完成后可在输入框试听，再点发送。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = { cancel() }) { Text("取消") } },
        confirmButton = {
            TextButton(enabled = !finishing, onClick = {
                if (recording) {
                    finishing = true
                    stop?.complete(Unit)
                } else {
                    error = null
                    elapsed = 0
                    recording = true
                    val signal = CompletableDeferred<Unit>()
                    stop = signal
                    job = scope.launch {
                        var temporary: File? = null
                        var saved = emptyList<Uri>()
                        try {
                            val file = File.createTempFile("recording_", ".wav", context.cacheDir)
                            temporary = file
                            recordRawAudio(file, signal)
                            finishing = true
                            withContext(Dispatchers.IO) {
                                saved = files.createChatFilesByContents(listOf(file.toUri()))
                            }
                            check(saved.size == 1) { "录音保存失败，请重试" }
                            onRecorded(saved.single())
                            saved = emptyList() // Ownership transfers to the input attachment.
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "录音失败，请检查麦克风权限"
                        } finally {
                            temporary?.delete()
                            if (saved.isNotEmpty()) files.deleteChatFiles(saved)
                            recording = false
                            finishing = false
                        }
                    }
                }
            }) { Text(if (finishing) "正在保存…" else if (recording) "完成录音" else "开始录音") }
        },
    )
}
