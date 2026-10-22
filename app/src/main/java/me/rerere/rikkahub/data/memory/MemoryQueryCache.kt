package me.rerere.rikkahub.data.memory

/** Embeddings only: do not cache retrieval results across memory edits/deletes. No raw queries or API keys. */
class MemoryQueryCache(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(val vector: List<Float>, val at: Long)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var bytes = 0

    @Synchronized
    fun get(key: String): List<Float>? {
        val entry = entries[key] ?: return null
        if (now() - entry.at >= 10 * 60 * 1000) {
            remove(key)
            return null
        }
        return entry.vector.toList()
    }

    @Synchronized
    fun put(key: String, vector: List<Float>) {
        if (vector.isEmpty() || vector.size > 8192 || vector.any { !it.isFinite() }) return
        remove(key)
        entries[key] = Entry(vector.toList(), now())
        bytes += vector.size * 4
        while (entries.size > 64 || bytes > 2 * 1024 * 1024) remove(entries.keys.first())
    }

    private fun remove(key: String) {
        entries.remove(key)?.let { bytes -= it.vector.size * 4 }
    }
}
