package me.rerere.rikkahub.data.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import me.rerere.rikkahub.utils.JsonInstant

/** Compatibility for indexes already written by v162/v163; retrieval otherwise uses the pre-speedup flow. */
internal object MemoryVectorCodec {
    fun encode(vector: List<Float>): ByteArray {
        require(vector.size in 1..8192 && vector.all { it.isFinite() } && vector.any { it != 0f })
        return ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            vector.forEach { putFloat(it) }
        }.array()
    }

    fun decode(legacy: String, packed: ByteArray?): FloatArray? = runCatching {
        if (packed == null && legacy.length > 512_000) return null
        val values = if (packed == null) JsonInstant.decodeFromString<List<Float>>(legacy).toFloatArray() else {
            if (packed.isEmpty() || packed.size > 8192 * 4 || packed.size % 4 != 0) return null
            val input = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(packed.size / 4) { input.float }
        }
        values.takeIf { it.size in 1..8192 && it.all(Float::isFinite) && it.any { value -> value != 0f } }
    }.getOrNull()
}
