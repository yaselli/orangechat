package me.rerere.rikkahub.data.ai

import org.junit.Assert.*
import org.junit.Test

class CompactionChunksTest {
    @Test
    fun `one long message is split losslessly with role and continuation labels`() {
        val text = "first\n" + "内容和空格 ".repeat(15_000) + "\nlast"
        val chunks = compactTextChunks(sequenceOf("USER" to text))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= COMPACT_CHUNK_CHARACTERS })
        assertTrue(chunks.first().startsWith("[USER]\n"))
        assertTrue(chunks.drop(1).all { it.startsWith("[USER; continuation of same message]\n") })
        assertEquals(text, chunks.joinToString("") { it.substringAfter('\n') })
    }

    @Test
    fun `surrogate pair at chunk boundary is not corrupted`() {
        val text = "x".repeat(COMPACT_CHUNK_CHARACTERS - "[USER]\n".length - 1) + "🥛" + "end"
        val chunks = compactTextChunks(sequenceOf("USER" to text))
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { !it.last().isHighSurrogate() })
        assertTrue(chunks.none { it.substringAfter('\n').first().isLowSurrogate() })
        assertEquals(text, chunks.joinToString("") { it.substringAfter('\n') })
    }

    @Test
    fun `short messages preserve order whitespace empty messages and existing format`() {
        val chunks = compactTextChunks(sequenceOf("USER" to " a\n ", "ASSISTANT" to "", "USER" to "b"))
        assertEquals(listOf("[USER]\n a\n \n\n[ASSISTANT]\n\n\n[USER]\nb"), chunks)
    }

    @Test
    fun `exactly eight full chunks fit but a ninth is rejected`() {
        val text = "a".repeat(COMPACT_CHUNK_CHARACTERS - "[USER]\n".length)
        val withinBudget = (1..MAX_COMPACT_CALLS).asSequence().map { "USER" to text }
        assertEquals(MAX_COMPACT_CALLS, compactTextChunks(withinBudget).size)
        try {
            compactTextChunks((1..MAX_COMPACT_CALLS + 1).asSequence().map { "USER" to text })
            fail("Must reject a ninth call")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `one message exceeding total operation budget is rejected without dropping its tail`() {
        try {
            compactTextChunks(sequenceOf("USER" to "a".repeat(COMPACT_CHUNK_CHARACTERS * MAX_COMPACT_CALLS)))
            fail("Must reject instead of silently truncating")
        } catch (_: IllegalArgumentException) { }
    }
}
