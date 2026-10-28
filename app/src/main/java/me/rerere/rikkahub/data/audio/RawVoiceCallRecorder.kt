package me.rerere.rikkahub.data.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * One coroutine owns the microphone. Pausing releases it; resuming cannot race a second recorder.
 */
@SuppressLint("MissingPermission")
internal suspend fun recordVoiceCallAudio(
    canCapture: () -> Boolean,
    captureEpoch: () -> Long,
    onFrame: (VoiceAudioSegmenter.Result, Long) -> Unit,
) =
    withContext(Dispatchers.IO) {
        val segmenter = VoiceAudioSegmenter()
        while (currentCoroutineContext().isActive) {
            if (!canCapture()) {
                segmenter.reset()
                delay(50)
                continue
            }
            val minimum =
                AudioRecord.getMinBufferSize(
                    RECORDING_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            check(minimum > 0) { "当前设备无法初始化原声录音" }
            val recorder =
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(RECORDING_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minimum, VoiceAudioSegmenter.FRAME_BYTES * 8))
                    .build()
            var echo: AcousticEchoCanceler? = null
            var noise: NoiseSuppressor? = null
            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风不可用，请检查权限" }
                echo =
                    runCatching {
                        if (AcousticEchoCanceler.isAvailable())
                            AcousticEchoCanceler.create(recorder.audioSessionId)
                        else null
                    }
                        .getOrNull()
                noise =
                    runCatching {
                        if (NoiseSuppressor.isAvailable())
                            NoiseSuppressor.create(recorder.audioSessionId)
                        else null
                    }
                        .getOrNull()
                runCatching { echo?.setEnabled(true) }
                runCatching { noise?.setEnabled(true) }
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始原声录音" }
                var epoch = captureEpoch()
                segmenter.reset()
                val buffer = ByteArray(VoiceAudioSegmenter.FRAME_BYTES)
                while (canCapture()) {
                    currentCoroutineContext().ensureActive()
                    val before = captureEpoch()
                    if (epoch != before) {
                        segmenter.reset()
                        epoch = before
                    }
                    var offset = 0
                    while (offset < buffer.size) {
                        val read =
                            recorder.read(
                                buffer,
                                offset,
                                buffer.size - offset,
                                AudioRecord.READ_BLOCKING,
                            )
                        check(read > 0) { "原声录音中断，请重新发起通话" }
                        offset += read
                        currentCoroutineContext().ensureActive()
                    }
                    if (!canCapture() || before != captureEpoch()) {
                        segmenter.reset()
                        continue
                    }
                    val result = segmenter.accept(buffer)
                    withContext(Dispatchers.Main.immediate) {
                        if (canCapture() && before == captureEpoch()) onFrame(result, before)
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { echo?.release() }
                runCatching { noise?.release() }
                runCatching { recorder.release() }
                segmenter.reset()
            }
        }
    }
