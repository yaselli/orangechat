package me.rerere.rikkahub.data.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import me.rerere.rikkahub.utils.JsonInstant

/** Independently implemented float32 encoding; old JSON indexes remain readable without a paid rebuild. */
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

internal class ScoringVector(val values: FloatArray) {
    val norm: Double = sqrt(values.sumOf { it.toDouble() * it })
    fun similarity(other: ScoringVector): Double? {
        if (values.size != other.values.size || norm <= 0 || other.norm <= 0) return null
        var dot = 0.0
        for (i in values.indices) dot += values[i].toDouble() * other.values[i]
        return (dot / (norm * other.norm)).takeIf { it.isFinite() && it > 0.0 }
    }
}

/** Cache vectors only, never source text. Refuse new admissions when full so sequential scans cannot thrash it. */
internal class MemoryParsedVectorCache(private val maxBytes: Int = 16 * 1024 * 1024) {
    private data class Entry(val legacy: String, val packed: ByteArray?, val vector: ScoringVector, val cost: Int)
    private val entries = mutableMapOf<String, Entry>()
    private var bytes = 0

    @Synchronized
    fun get(key: String, legacy: String, packed: ByteArray?): ScoringVector? {
        entries[key]?.let { cached ->
            if (cached.legacy == legacy && (cached.packed?.contentEquals(packed ?: byteArrayOf()) ?: (packed == null))) {
                return cached.vector
            }
            bytes -= cached.cost
            entries.remove(key)
        }
        val values = MemoryVectorCodec.decode(legacy, packed) ?: return null
        val vector = ScoringVector(values)
        val cost = values.size * 4 + (packed?.size ?: legacy.length * 2) + key.length * 2 + 128
        if (bytes + cost <= maxBytes) {
            entries[key] = Entry(legacy, packed?.copyOf(), vector, cost)
            bytes += cost
        }
        return vector
    }

    @Synchronized internal fun sizeBytes(): Int = bytes
}
