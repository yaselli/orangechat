package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageTest {
    @Test
    fun `merge combines partial snapshots within one request`() {
        val prompt = TokenUsage(promptTokens = 20, cachedTokens = 8)
        val result = prompt.merge(TokenUsage(completionTokens = 5))
        assertEquals(TokenUsage(20, 5, 8, 25), result)
        assertEquals(result, result.merge(result))
    }

    @Test
    fun `sum adds independent requests including their reported totals`() {
        assertEquals(TokenUsage(30, 8, 6, 40), TokenUsage(10, 3, 2, 15).sum(TokenUsage(20, 5, 4, 25)))
        val usage = TokenUsage(10, 3, 2, 13)
        assertEquals(usage, null.sum(usage))
        assertEquals(usage, usage.sum(null))
        assertNull(null.sum(null))
    }
}
