package me.rerere.rikkahub.ui.pages.stats

import me.rerere.rikkahub.data.db.dao.UsageBucket
import me.rerere.rikkahub.data.db.dao.UsageTotals
import java.time.LocalDate
import java.time.ZoneId

enum class UsageRange(val days: Int?) {
    WEEK(7), MONTH(30), QUARTER(90), ALL(null),
}

data class UsagePoint(val date: LocalDate, val input: Long = 0, val output: Long = 0) {
    val total: Long get() = input + output
}

data class UsageRank(val name: String, val input: Long, val output: Long, val cached: Long) {
    val total: Long get() = input + output
}

data class RequestUsageStats(
    val range: UsageRange = UsageRange.MONTH,
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val totals: UsageTotals = UsageTotals(),
    val points: List<UsagePoint> = emptyList(),
    val models: List<UsageRank> = emptyList(),
    val sources: List<UsageRank> = emptyList(),
)

internal fun usageSince(range: UsageRange, today: LocalDate, zone: ZoneId): Long =
    range.days?.let { today.minusDays(it - 1L).atStartOfDay(zone).toInstant().toEpochMilli() } ?: 0L

/** Fill empty dates in the chart; ALL uses months to keep long histories readable. */
internal fun usagePoints(range: UsageRange, buckets: List<UsageBucket>, today: LocalDate): List<UsagePoint> {
    val monthly = range == UsageRange.ALL
    val byDate = buckets.mapNotNull { bucket ->
        runCatching {
            val date = LocalDate.parse(if (monthly) "${bucket.bucket}-01" else bucket.bucket)
            date to UsagePoint(date, bucket.promptTokens, bucket.completionTokens)
        }.getOrNull()
    }.toMap()
    val end = if (monthly) today.withDayOfMonth(1) else today
    val start = range.days?.let { today.minusDays(it - 1L) }
        ?: byDate.keys.minOrNull()?.coerceAtMost(end) ?: end
    return generateSequence(start) { if (monthly) it.plusMonths(1) else it.plusDays(1) }
        .takeWhile { !it.isAfter(end) }
        .map { byDate[it] ?: UsagePoint(it) }
        .toList()
}
