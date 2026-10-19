package me.rerere.rikkahub.data.memory

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MemoryRetentionTest {
    @Test fun `history loses half its weight per half life and expires while disabled policy leaves history intact`() {
        val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
        val policy = MemoryRetentionPolicy(now)
        assertEquals(1.0, policy.weight("2026-10-19T12:00:00Z"), 0.0001)
        assertEquals(0.5, policy.weight("2026-10-12T12:00:00Z"), 0.0001)
        assertEquals(0.25, policy.weight("2026-10-05T12:00:00Z"), 0.0001)
        assertEquals(0.0, policy.weight("2020-01-01T12:00:00Z"), 0.0)
        assertFalse(policy.allows("invalid date"))
        assertTrue(policy.copy(enabled = false).allows("2020-01-01"))
        val source = HistoryMemorySource("old", "node", "message", "owner", "user", "2020-01-01T00:00", "原文", 0)
        val boundary = HistoryRecallBoundary("current", 0, "2026-10-20T00:00", emptySet(), policy.cutoffMillis)
        assertFalse(boundary.allows(source))
        assertTrue(boundary.copy(oldestMillis = null).allows(source))
    }
}
