package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Start is inclusive, end is exclusive. Equal endpoints disable the quiet window. */
internal fun isProactiveQuietTime(setting: ProactiveMessageSetting, millis: Long, zone: ZoneId): Boolean {
    if (!setting.quietHoursEnabled) return false
    val start = setting.quietStartMinute.coerceIn(0, 1439)
    val end = setting.quietEndMinute.coerceIn(0, 1439)
    val local = Instant.ofEpochMilli(millis).atZone(zone)
    val minute = local.hour * 60 + local.minute
    return when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }
}

internal fun nextProactiveAllowedTime(setting: ProactiveMessageSetting, millis: Long, zone: ZoneId): Long {
    if (!isProactiveQuietTime(setting, millis, zone)) return millis
    val local = Instant.ofEpochMilli(millis).atZone(zone)
    val start = setting.quietStartMinute.coerceIn(0, 1439)
    val end = setting.quietEndMinute.coerceIn(0, 1439)
    val wrapsMidnight = start > end
    val endDate = if (wrapsMidnight && local.hour * 60 + local.minute >= start) {
        local.toLocalDate().plusDays(1)
    } else local.toLocalDate()
    var resume = endDate.atTime(LocalTime.of(end / 60, end % 60)).atZone(zone)
    // During a repeated DST hour, the first occurrence can already be in the past.
    if (resume.toInstant().toEpochMilli() <= millis) resume = resume.withLaterOffsetAtOverlap()
    return resume.toInstant().toEpochMilli()
}

internal fun nextProactiveCheckTime(
    setting: ProactiveMessageSetting,
    now: Long,
    delayMinutes: Int,
    zone: ZoneId,
): Long = nextProactiveAllowedTime(
    setting, now + TimeUnit.MINUTES.toMillis(delayMinutes.coerceAtLeast(1).toLong()), zone,
)

/** A manual test can bypass the daily window, never the user's master off switch. */
internal fun proactiveScheduleBlockReason(
    setting: ProactiveMessageSetting,
    millis: Long,
    zone: ZoneId,
    manualTest: Boolean,
): String? = when {
    !setting.enabled -> "disabled"
    !manualTest && isProactiveQuietTime(setting, millis, zone) -> "quiet_hours"
    else -> null
}

internal class ProactiveScheduleBlocked(val reason: String) : RuntimeException(reason)
