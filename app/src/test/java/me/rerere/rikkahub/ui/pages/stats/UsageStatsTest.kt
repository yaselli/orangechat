package me.rerere.rikkahub.ui.pages.stats

import me.rerere.rikkahub.data.db.dao.UsageBucket
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class UsageStatsTest {
    @Test
    fun `range starts at local midnight and includes today rather than a rolling hour window`() {
        val today = LocalDate.of(2026, 10, 23)
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals(today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli(),
            usageSince(UsageRange.WEEK, today, zone))
        assertEquals(today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli(),
            usageSince(UsageRange.MONTH, today, zone))
        assertEquals(today.minusDays(89).atStartOfDay(zone).toInstant().toEpochMilli(),
            usageSince(UsageRange.QUARTER, today, zone))
        assertEquals(0L, usageSince(UsageRange.ALL, today, zone))
    }

    @Test
    fun `day ranges survive daylight saving boundaries`() {
        val today = LocalDate.of(2026, 3, 9)
        val zone = ZoneId.of("America/New_York")
        assertEquals(LocalDate.of(2026, 3, 3).atStartOfDay(zone).toInstant().toEpochMilli(),
            usageSince(UsageRange.WEEK, today, zone))
        assertEquals(7, usagePoints(UsageRange.WEEK, emptyList(), today).size)
    }

    @Test
    fun `daily chart fills gaps and never adds cached input a second time`() {
        val today = LocalDate.of(2026, 10, 23)
        val points = usagePoints(UsageRange.WEEK, listOf(
            UsageBucket("2026-10-17", 20, 5, 10),
            UsageBucket("2026-10-23", 40, 10, 30),
        ), today)
        assertEquals(7, points.size)
        assertEquals(25L, points.first().total)
        assertEquals(0L, points[1].total)
        assertEquals(50L, points.last().total)
        assertEquals(75L, points.sumOf { it.total })
    }

    @Test
    fun `all time chart crosses years with empty months and handles no records`() {
        val today = LocalDate.of(2026, 2, 3)
        val points = usagePoints(UsageRange.ALL, listOf(UsageBucket("2025-12", 20, 5, 10)), today)
        assertEquals(listOf("2025-12-01", "2026-01-01", "2026-02-01"), points.map { it.date.toString() })
        assertEquals(listOf(25L, 0L, 0L), points.map { it.total })
        assertEquals(listOf(UsagePoint(today.withDayOfMonth(1))), usagePoints(UsageRange.ALL, emptyList(), today))
    }
}
