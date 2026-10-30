package me.rerere.rikkahub.data.audio

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/** Local energy detection only: original PCM samples are kept, never transcribed or synthesized. */
internal class VoiceAudioSegmenter {
    data class Result(
        val level: Float,
        val speechStarted: Boolean = false,
        val pcm: ByteArray? = null,
    )

    private val preRoll = ArrayDeque<ByteArray>()
    private val utterance = ByteArrayOutputStream()
    private var active = false
    private var onsetFrames = 0
    private var speechFrames = 0
    private var silenceFrames = 0
    private var totalFrames = 0

    fun reset() {
        preRoll.clear()
        utterance.reset()
        active = false
        onsetFrames = 0
        speechFrames = 0
        silenceFrames = 0
        totalFrames = 0
    }

    fun accept(frame: ByteArray): Result {
        require(frame.size == FRAME_BYTES) { "Expected a complete 20 ms PCM frame" }
        val level = pcmLevel(frame)
        val voiced = level >= SPEECH_THRESHOLD
        var started = false
        if (!active) {
            preRoll.addLast(frame.copyOf())
            while (preRoll.size > 10) preRoll.removeFirst()
            onsetFrames = if (voiced) onsetFrames + 1 else 0
            if (onsetFrames < 3) return Result(level)
            active = true
            started = true
            speechFrames = onsetFrames
            totalFrames = preRoll.size
            preRoll.forEach { utterance.write(it) }
            preRoll.clear()
        } else {
            utterance.write(frame)
            totalFrames++
            if (voiced) speechFrames++
        }
        silenceFrames = if (voiced) 0 else silenceFrames + 1
        if (silenceFrames >= 45 || totalFrames >= MAX_FRAMES) {
            val pcm = utterance.toByteArray().takeIf { speechFrames >= 10 }
            reset()
            return Result(level, started, pcm)
        }
        return Result(level, started)
    }

    companion object {
        const val FRAME_BYTES = 640 // 16 kHz, mono, signed little-endian PCM16, 20 ms.
        const val MAX_FRAMES = 1500 // At most 30 seconds / 960 kB per utterance.
        private const val SPEECH_THRESHOLD = 0.012f

        fun pcmLevel(frame: ByteArray): Float {
            require(frame.isNotEmpty() && frame.size % 2 == 0)
            var squares = 0.0
            for (at in frame.indices step 2) {
                val sample =
                    ((frame[at].toInt() and 0xff) or (frame[at + 1].toInt() shl 8)).toShort()
                val normalized = sample.toDouble() / 32768.0
                squares += normalized * normalized
            }
            return sqrt(squares / (frame.size / 2)).toFloat()
        }
    }
}
