package me.rerere.rikkahub.data.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryVectorCodecTest {
    @Test
    fun `binary float32 vectors round trip and old JSON vectors remain readable`() {
        val values = listOf(1f, -0.5f, 0.25f)
        val packed = MemoryVectorCodec.encode(values)
        assertEquals(12, packed.size)
        assertEquals(values, MemoryVectorCodec.decode("", packed)!!.toList())
        assertEquals(values, MemoryVectorCodec.decode("[1,-0.5,0.25]", null)!!.toList())
    }

    @Test
    fun `malformed non finite zero and oversized vectors cannot enter scoring`() {
        for (packed in listOf(byteArrayOf(), byteArrayOf(1), ByteArray(8193 * 4))) {
            assertNull(MemoryVectorCodec.decode("[1,0]", packed))
        }
        for (json in listOf("broken", "[]", "[0,0]", "[1e999,1]")) assertNull(MemoryVectorCodec.decode(json, null))
    }

    @Test
    fun `cache reuses parsed vectors but content changes replace or reject stale entries`() {
        val cache = MemoryParsedVectorCache()
        val first = cache.get("scope/model/slice", "[1,0]", null)!!
        assertSame(first, cache.get("scope/model/slice", "[1,0]", null))
        val changed = cache.get("scope/model/slice", "", MemoryVectorCodec.encode(listOf(0f, 1f)))!!
        assertEquals(listOf(0f, 1f), changed.values.toList())
        assertNull(cache.get("scope/model/slice", "broken", null))
        assertNotNull(cache.get("scope/model/slice", "[1,0]", null))
    }

    @Test
    fun `bounded cache does not evict warm entries during a sequential oversized scan`() {
        val cache = MemoryParsedVectorCache(1024)
        val first = cache.get("first", "[1,0]", null)!!
        repeat(100) { cache.get("other-$it", "[1,0]", null) }
        assertTrue(cache.sizeBytes() <= 1024)
        assertSame(first, cache.get("first", "[1,0]", null))
    }

    @Test
    fun `primitive array scoring agrees with the existing cosine and rejects dimension mismatch`() {
        val a = listOf(1f, 2f, 3f)
        val b = listOf(3f, 1f, 2f)
        assertEquals(memoryCosine(a, b)!!, ScoringVector(a.toFloatArray()).similarity(ScoringVector(b.toFloatArray()))!!, 1e-12)
        assertNull(ScoringVector(floatArrayOf(1f)).similarity(ScoringVector(floatArrayOf(1f, 0f))))
        assertNull(ScoringVector(floatArrayOf(1f)).similarity(ScoringVector(floatArrayOf(-1f))))
    }

    @Test
    fun `faster SHA256 formatting preserves existing source and slice identities`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", memoryHash(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", memoryHash("abc"))
        for (text in listOf("吃辣🌶", "user\u00002026-10-20\u0000source")) {
            val old = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(old, memoryHash(text))
        }
    }
}
