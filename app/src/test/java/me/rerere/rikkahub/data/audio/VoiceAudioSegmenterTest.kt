package me.rerere.rikkahub.data.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class VoiceAudioSegmenterTest {
    private fun frame(value: Int): ByteArray =
        ByteBuffer.allocate(VoiceAudioSegmenter.FRAME_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { repeat(320) { putShort(value.toShort()) } }
            .array()

    @Test
    fun `silence never becomes a user message`() {
        val detector = VoiceAudioSegmenter()
        repeat(5000) {
            val result = detector.accept(frame(0))
            assertFalse(result.speechStarted)
            assertNull(result.pcm)
        }
    }

    @Test
    fun `pause emits exact original samples including pre roll with one onset`() {
        val detector = VoiceAudioSegmenter()
        val quiet = frame(0)
        val voice = frame(2000)
        val inputs = List(10) { quiet } + List(15) { voice } + List(45) { quiet }
        val results = inputs.map { detector.accept(it) }
        assertEquals(1, results.count { it.speechStarted })
        assertEquals(1, results.count { it.pcm != null })
        assertArrayEquals(
            inputs.drop(3).fold(ByteArray(0)) { all, next -> all + next },
            results.last().pcm,
        )
        assertNull(detector.accept(quiet).pcm) // No repeated send during continued silence.
    }

    @Test
    fun `clicks and very short bursts are discarded`() {
        val detector = VoiceAudioSegmenter()
        repeat(3) { detector.accept(frame(3000)) }
        repeat(90) { assertNull(detector.accept(frame(0)).pcm) }
    }

    @Test
    fun `brief pauses remain inside one original utterance`() {
        val detector = VoiceAudioSegmenter()
        val frames =
            List(12) { frame(1600) } +
                List(30) { frame(0) } +
                List(12) { frame(-2100) } +
                List(45) { frame(0) }
        val results = frames.map(detector::accept)
        assertEquals(1, results.count { it.speechStarted })
        assertEquals(1, results.count { it.pcm != null })
        assertArrayEquals(frames.fold(ByteArray(0)) { all, next -> all + next }, results.last().pcm)
    }

    @Test
    fun `continuous speech has a fixed memory and attachment bound`() {
        val detector = VoiceAudioSegmenter()
        val voice = frame(1700)
        repeat(VoiceAudioSegmenter.MAX_FRAMES - 1) { assertNull(detector.accept(voice).pcm) }
        assertEquals(960_000, detector.accept(voice).pcm!!.size)
        assertTrue(detector.accept(voice).pcm == null)
        assertTrue(detector.accept(voice).pcm == null)
        assertTrue(detector.accept(voice).speechStarted)
    }

    @Test
    fun `mute reset discards all buffered samples before resuming`() {
        val detector = VoiceAudioSegmenter()
        repeat(20) { detector.accept(frame(3200)) }
        detector.reset()
        repeat(12) { detector.accept(frame(-1700)) }
        val results = List(45) { detector.accept(frame(0)) }
        val expected = List(12) { frame(-1700) } + List(45) { frame(0) }
        assertArrayEquals(
            expected.fold(ByteArray(0)) { all, next -> all + next },
            results.last().pcm,
        )
    }

    @Test
    fun `onset requires sustained input rather than single loud frames`() {
        val detector = VoiceAudioSegmenter()
        repeat(100) {
            assertFalse(detector.accept(frame(4000)).speechStarted)
            assertFalse(detector.accept(frame(0)).speechStarted)
        }
    }

    @Test
    fun `RMS reads signed little endian PCM and rejects incomplete frames`() {
        assertEquals(0f, VoiceAudioSegmenter.pcmLevel(frame(0)), 0.00001f)
        assertEquals(1f, VoiceAudioSegmenter.pcmLevel(frame(-32768)), 0.00001f)
        assertEquals(0.5f, VoiceAudioSegmenter.pcmLevel(frame(16384)), 0.00001f)
        for (size in listOf(0, 1, 639, 642)) {
            assertTrue(runCatching { VoiceAudioSegmenter().accept(ByteArray(size)) }.isFailure)
        }
    }
}
