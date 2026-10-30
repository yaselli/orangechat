package me.rerere.rikkahub.service

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.audio.pcmWavHeader
import me.rerere.rikkahub.data.audio.recordVoiceCallAudio
import me.rerere.rikkahub.data.files.FilesManager

/** Owns native input only. The normal conversation queue, model pipeline and TTS own the reply. */
internal class RawVoiceCallInput(
    private val context: Context,
    private val scope: CoroutineScope,
    private val files: FilesManager,
    private val canCapture: () -> Boolean,
    private val onLevel: (Float) -> Unit,
    private val onSpeechStarted: () -> Unit,
    private val onSending: () -> Unit,
    private val onAudioReady: (List<UIMessagePart>) -> Boolean,
    private val onFailure: (String) -> Unit,
) {
    private val epoch = AtomicLong(0)
    private var captureJob: Job? = null
    private var sendJob: Job? = null
    @Volatile private var closed = false

    fun start() {
        if (closed || captureJob?.isActive == true) return
        captureJob = scope.launch {
            try {
                recordVoiceCallAudio(
                    canCapture = { !closed && canCapture() },
                    captureEpoch = epoch::get,
                ) { result, frameEpoch ->
                    if (closed || frameEpoch != epoch.get() || !canCapture())
                        return@recordVoiceCallAudio
                    onLevel((result.level * 8).coerceIn(0f, 1f))
                    if (result.speechStarted) onSpeechStarted()
                    result.pcm?.let(::send)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed) onFailure(failure.message ?: "原声录音失败")
            }
        }
    }

    /** A mute/settings transition invalidates buffered frames even if it occurred between reads. */
    fun invalidateCapture() {
        epoch.incrementAndGet()
    }

    fun interruptPendingSend() {
        sendJob?.cancel()
    }

    private fun send(pcm: ByteArray) {
        if (closed || !canCapture()) return
        sendJob?.cancel()
        onSending()
        sendJob = scope.launch {
            var temporary: File? = null
            var owned = emptyList<android.net.Uri>()
            try {
                // Retain the resulting URIs even if cancellation arrives while copying the file.
                withContext(NonCancellable + Dispatchers.IO) {
                    val file = File.createTempFile("voice_call_", ".wav", context.cacheDir)
                    temporary = file
                    file.outputStream().use { output ->
                        output.write(pcmWavHeader(pcm.size))
                        output.write(pcm)
                    }
                    owned = files.createChatFilesByContents(listOf(file.toUri()))
                }
                currentCoroutineContext().ensureActive()
                check(owned.size == 1) { "原声录音保存失败" }
                if (!closed && onAudioReady(rawVoiceCallParts(owned.single().toString()))) {
                    owned =
                        emptyList() // Ownership transfers to the existing message queue/history.
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed) onFailure(failure.message ?: "原声发送失败")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    temporary?.delete()
                    if (owned.isNotEmpty()) files.deleteChatFiles(owned)
                }
            }
        }
    }

    fun close() {
        closed = true
        invalidateCapture()
        captureJob?.cancel()
        sendJob?.cancel()
    }
}

internal fun rawVoiceCallParts(url: String): List<UIMessagePart> {
    require(url.startsWith("file:")) { "原声通话只接受已保存的本地录音" }
    return listOf(
        UIMessagePart.Text("[语音通话原声] 请直接聆听音频并自然回应。"),
        UIMessagePart.Audio(url),
    )
}
