package me.rerere.rikkahub.data.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val RECORDING_SAMPLE_RATE = 16_000
internal const val RECORDING_MAX_SECONDS = 120

internal fun pcmWavHeader(pcmBytes: Int): ByteArray {
    require(pcmBytes >= 0 && pcmBytes % 2 == 0)
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + pcmBytes)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1.toShort()) // PCM
        putShort(1.toShort()) // mono
        putInt(RECORDING_SAMPLE_RATE)
        putInt(RECORDING_SAMPLE_RATE * 2)
        putShort(2.toShort())
        putShort(16.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(pcmBytes)
    }.array()
}
