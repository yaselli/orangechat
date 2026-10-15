package me.rerere.rikkahub.data.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** A local WAV recording only. No ASR service or network connection is involved. */
@SuppressLint("MissingPermission")
internal suspend fun recordRawAudio(file: File, stop: CompletableDeferred<Unit>): Unit = withContext(Dispatchers.IO) {
    val minimum = AudioRecord.getMinBufferSize(
        RECORDING_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    )
    check(minimum > 0) { "当前设备无法初始化录音" }
    val recorder = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(AudioFormat.Builder()
            .setSampleRate(RECORDING_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build())
        .setBufferSizeInBytes(maxOf(minimum, 4096))
        .build()
    try {
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风不可用，请检查权限或其他录音应用" }
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0)
            output.write(pcmWavHeader(0))
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音" }
            val buffer = ByteArray(4096)
            val maximum = RECORDING_SAMPLE_RATE * 2 * RECORDING_MAX_SECONDS
            var count = 0
            while (!stop.isCompleted && count < maximum) {
                currentCoroutineContext().ensureActive()
                val read = recorder.read(buffer, 0, minOf(buffer.size, maximum - count), AudioRecord.READ_BLOCKING)
                check(read > 0) { "录音中断，请重新录制" }
                output.write(buffer, 0, read)
                count += read
            }
            currentCoroutineContext().ensureActive()
            check(count >= RECORDING_SAMPLE_RATE / 2) { "录音太短，请重新录制" }
            output.seek(0)
            output.write(pcmWavHeader(count))
        }
    } finally {
        runCatching { recorder.stop() }
        recorder.release()
    }
}
