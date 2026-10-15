package me.rerere.rikkahub.data.audio

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavRecordingTest {
    @Test
    fun `recording header describes mono 16 bit pcm and the exact data length`() {
        val pcmBytes = RECORDING_SAMPLE_RATE * 2 * 3
        val bytes = pcmWavHeader(pcmBytes)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, bytes.size)
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals(36 + pcmBytes, header.getInt(4))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(pcmBytes, header.getInt(40))
    }

    @Test
    fun `negative or incomplete samples are rejected`() {
        for (size in listOf(-2, 1, 3)) {
            try {
                pcmWavHeader(size)
                fail("PCM must have complete samples")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
