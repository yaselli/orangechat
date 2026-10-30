package me.rerere.rikkahub.data.service

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ProactiveScheduleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val daytime = ProactiveMessageSetting(enabled = true, quietHoursEnabled = true)

    private fun at(time: String): Long = java.time.LocalDateTime.parse("2026-10-25T$time")
        .atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `09 to 18 is quiet including start and excluding end`() {
        assertFalse(isProactiveQuietTime(daytime, at("08:59:59"), zone))
        assertTrue(isProactiveQuietTime(daytime, at("09:00:00"), zone))
        assertTrue(isProactiveQuietTime(daytime, at("17:59:59"), zone))
        assertFalse(isProactiveQuietTime(daytime, at("18:00:00"), zone))
        assertEquals(at("18:00:00"), nextProactiveAllowedTime(daytime, at("12:34:56"), zone))
    }

    @Test
    fun `candidate falling inside quiet hours moves both schedulers to the same end instant`() {
        assertEquals(at("18:00:00"), nextProactiveCheckTime(daytime, at("08:45:00"), 30, zone))
        assertEquals(at("18:10:00"), nextProactiveCheckTime(daytime, at("17:40:00"), 30, zone))
        assertEquals(at("19:00:00"), nextProactiveCheckTime(daytime, at("08:00:00"), 660, zone))
    }

    @Test
    fun `cross midnight quiet window resumes on the appropriate calendar day`() {
        val overnight = daytime.copy(quietStartMinute = 23 * 60, quietEndMinute = 8 * 60)
        assertFalse(isProactiveQuietTime(overnight, at("22:59:59"), zone))
        assertTrue(isProactiveQuietTime(overnight, at("23:00:00"), zone))
        assertTrue(isProactiveQuietTime(overnight, at("00:01:00"), zone))
        assertFalse(isProactiveQuietTime(overnight, at("08:00:00"), zone))
        assertEquals(at("08:00:00"), nextProactiveAllowedTime(overnight, at("07:30:00"), zone))
        val tomorrow = java.time.LocalDateTime.parse("2026-10-26T08:00:00")
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(tomorrow, nextProactiveAllowedTime(overnight, at("23:30:00"), zone))
    }

    @Test
    fun `disabled quiet hours and equal endpoints preserve normal intervals`() {
        for (setting in listOf(daytime.copy(quietHoursEnabled = false), daytime.copy(quietEndMinute = 9 * 60))) {
            assertFalse(isProactiveQuietTime(setting, at("12:00:00"), zone))
            assertEquals(at("12:30:00"), nextProactiveCheckTime(setting, at("12:00:00"), 30, zone))
        }
    }

    @Test
    fun `manual tests bypass quiet hours but never reopen a disabled master switch`() {
        assertEquals("quiet_hours", proactiveScheduleBlockReason(daytime, at("12:00:00"), zone, false))
        assertNull(proactiveScheduleBlockReason(daytime, at("12:00:00"), zone, true))
        val disabled = daytime.copy(enabled = false)
        assertEquals("disabled", proactiveScheduleBlockReason(disabled, at("12:00:00"), zone, true))
        assertEquals("disabled", proactiveScheduleBlockReason(disabled, at("19:00:00"), zone, false))
        assertFalse(disabled.enabled)
    }

    @Test
    fun `late background delivery is blocked even if the alarm was created before quiet hours`() {
        assertNull(proactiveScheduleBlockReason(daytime, at("08:59:59"), zone, false))
        assertEquals("quiet_hours", proactiveScheduleBlockReason(daytime, at("09:01:00"), zone, false))
        assertNull(proactiveScheduleBlockReason(daytime, at("18:01:00"), zone, false))
    }

    @Test
    fun `minute precision and all minutes of an overnight day obey the window`() {
        val overnight = daytime.copy(quietStartMinute = 23 * 60 + 15, quietEndMinute = 8 * 60 + 45)
        val start = at("00:00:00")
        for (minute in 0 until 1440) {
            val now = start + minute * 60_000L
            val resumed = nextProactiveAllowedTime(overnight, now, zone)
            assertTrue("minute=$minute", resumed >= now)
            assertFalse("minute=$minute", isProactiveQuietTime(overnight, resumed, zone))
            if (!isProactiveQuietTime(overnight, now, zone)) assertEquals(now, resumed)
        }
    }

    @Test
    fun `phone timezone rather than UTC determines the daily window`() {
        val instant = Instant.parse("2026-10-25T01:00:00Z").toEpochMilli()
        assertTrue(isProactiveQuietTime(daytime, instant, zone))
        assertFalse(isProactiveQuietTime(daytime, instant, ZoneId.of("UTC")))
    }

    @Test
    fun `DST repeated end hour never schedules the resume into the past`() {
        val newYork = ZoneId.of("America/New_York")
        val setting = daytime.copy(quietStartMinute = 0, quietEndMinute = 90)
        val secondOccurrence = Instant.parse("2026-11-01T06:15:00Z").toEpochMilli()
        val resume = nextProactiveAllowedTime(setting, secondOccurrence, newYork)
        assertEquals(Instant.parse("2026-11-01T06:30:00Z").toEpochMilli(), resume)
        assertFalse(isProactiveQuietTime(setting, resume, newYork))
    }

    @Test
    fun `existing settings decode with scheduling disabled and round trip preserves the chosen window`() {
        val old = Json.decodeFromString<ProactiveMessageSetting>("""{"enabled":true,"minIntervalMinutes":15}""")
        assertFalse(old.quietHoursEnabled)
        assertEquals(15, old.minIntervalMinutes)
        val chosen = daytime.copy(quietStartMinute = 23 * 60 + 15, quietEndMinute = 8 * 60 + 45)
        val encoded = Json.encodeToString(ProactiveMessageSetting.serializer(), chosen)
        assertEquals(chosen, Json.decodeFromString<ProactiveMessageSetting>(encoded))
    }
}
