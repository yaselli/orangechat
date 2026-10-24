package me.rerere.rikkahub.data.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryQueryCacheTest {
    @Test
    fun `cache expires and model or scope keys remain independent`() {
        var now = 0L
        val cache = MemoryQueryCache { now }
        cache.put("a", listOf(1f))
        assertEquals(listOf(1f), cache.get("a"))
        assertNull(cache.get("b"))
        now = 600_000
        assertNull(cache.get("a"))
    }

    @Test
    fun `LRU bound retains recently used keys and never exposes mutable stored vectors`() {
        val cache = MemoryQueryCache()
        val mutable = mutableListOf(1f)
        cache.put("0", mutable)
        mutable[0] = 2f
        assertEquals(listOf(1f), cache.get("0"))
        for (id in 1..63) cache.put(id.toString(), listOf(1f))
        cache.get("0")
        cache.put("new", listOf(1f))
        assertNull(cache.get("1"))
        assertNotNull(cache.get("0"))
        cache.put("nan", listOf(Float.NaN))
        cache.put("huge", List(8193) { 1f })
        assertNull(cache.get("nan"))
        assertNull(cache.get("huge"))
    }
}
