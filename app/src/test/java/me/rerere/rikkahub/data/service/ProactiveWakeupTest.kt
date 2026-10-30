package me.rerere.rikkahub.data.service

import java.time.Instant
import java.time.ZoneId
import me.rerere.rikkahub.data.datastore.*
import org.junit.Assert.*
import org.junit.Test

class ProactiveWakeupTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(time: String) = Instant.parse(time).toEpochMilli()

    private val now = at("2026-10-28T00:00:00Z")

    private fun wakeup(id: String = "one", due: Long = now) =
        ProactiveWakeup(id, "role", "chat", due, zone.id, "问问今天过得怎么样")

    private fun setting(vararg tasks: ProactiveWakeup) =
        ProactiveMessageSetting(enabled = true, allowAiWakeups = true, aiWakeups = tasks.toList())

    @Test
    fun `phone local time includes date and resolves to the expected instant`() {
        assertEquals(
            at("2026-10-28T13:30:00Z"),
            parseProactiveWakeupTime("2026-10-28 21:30", zone, now),
        )
    }

    @Test
    fun `past times missing dates and invalid dates are rejected`() {
        for (text in listOf("2026-10-28 07:59", "21:30", "2026-02-30 21:30", "2026-10-28 25:00")) {
            assertThrows(Exception::class.java) { parseProactiveWakeupTime(text, zone, now) }
        }
    }

    @Test
    fun `DST gaps are rejected and a repeated hour chooses a future occurrence`() {
        val ny = ZoneId.of("America/New_York")
        assertThrows(IllegalArgumentException::class.java) {
            parseProactiveWakeupTime("2026-03-08 02:30", ny, at("2026-03-08T05:00:00Z"))
        }
        assertEquals(
            at("2026-11-01T06:30:00Z"),
            parseProactiveWakeupTime("2026-11-01 01:30", ny, at("2026-11-01T05:45:00Z")),
        )
    }

    @Test
    fun `new agreement replaces only a pending appointment in the same role and conversation`() {
        val one = wakeup()
        val otherChat = wakeup("other").copy(conversationId = "other-chat")
        val otherRole = wakeup("role2").copy(assistantId = "other-role")
        val running = wakeup("active").copy(status = ProactiveWakeupStatus.Running)
        val next = listOf(one, otherChat, otherRole, running).scheduleWakeup(wakeup("new"))
        assertEquals(ProactiveWakeupStatus.Cancelled, next.first { it.id == one.id }.status)
        assertEquals(otherChat, next.first { it.id == otherChat.id })
        assertEquals(otherRole, next.first { it.id == otherRole.id })
        assertEquals(running, next.first { it.id == running.id })
        assertEquals(ProactiveWakeupStatus.Pending, next.first { it.id == "new" }.status)
    }

    @Test
    fun `master and opt in switches both gate scheduling and claims`() {
        val active = setting(wakeup())
        for (blocked in listOf(active.copy(enabled = false), active.copy(allowAiWakeups = false))) {
            assertNull(nextAiProactiveWakeup(blocked, now, zone))
            assertNull(claimAiProactiveWakeup(blocked, "one", now, zone).wakeup)
        }
    }

    @Test
    fun `appointment in quiet hours waits until the daily window ends`() {
        val due = wakeup(due = at("2026-10-28T04:30:00Z"))
        val s = setting(due).copy(quietHoursEnabled = true)
        assertEquals(at("2026-10-28T10:00:00Z"), aiProactiveWakeupTime(s, due, now, zone))
        assertNull(claimAiProactiveWakeup(s, due.id, due.atMillis, zone).wakeup)
        assertNotNull(claimAiProactiveWakeup(s, due.id, at("2026-10-28T10:00:00Z"), zone).wakeup)
    }

    @Test
    fun `cross midnight quiet window defers an appointment to the next morning`() {
        val task = wakeup(due = at("2026-10-28T15:30:00Z"))
        val s =
            setting(task)
                .copy(quietHoursEnabled = true, quietStartMinute = 23 * 60, quietEndMinute = 8 * 60)
        assertEquals(at("2026-10-29T00:00:00Z"), aiProactiveWakeupTime(s, task, now, zone))
    }

    @Test
    fun `not yet due or replaced task cannot be claimed`() {
        assertNull(
            claimAiProactiveWakeup(setting(wakeup(due = now + 60_000)), "one", now, zone).wakeup
        )
        assertNull(claimAiProactiveWakeup(setting(wakeup("new")), "old", now, zone).wakeup)
    }

    @Test
    fun `a persisted claim prevents duplicate alarm and worker execution`() {
        val first = claimAiProactiveWakeup(setting(wakeup()), "one", now, zone)
        assertEquals(ProactiveWakeupStatus.Running, first.wakeup!!.status)
        assertEquals(now, first.wakeup.startedAtMillis)
        assertNull(claimAiProactiveWakeup(first.setting, "one", now, zone).wakeup)
    }

    @Test
    fun `earliest pending appointment wins regardless of conversation`() {
        val s = setting(wakeup("later", now + 60_000), wakeup("earlier", now))
        assertEquals("earlier", nextAiProactiveWakeup(s, now, zone)!!.id)
    }

    @Test
    fun `cancellation stays cancelled when an old run finishes`() {
        val cancelled = wakeup().copy(status = ProactiveWakeupStatus.Cancelled)
        assertEquals(
            cancelled,
            setting(cancelled).finishWakeup(cancelled.id, "sent", now).aiWakeups.single(),
        )
    }

    @Test
    fun `success and failure finish only their own running appointment`() {
        val active = wakeup().copy(status = ProactiveWakeupStatus.Running)
        val next = wakeup("next", now + 60_000)
        val s = setting(active, next)
        assertEquals(
            ProactiveWakeupStatus.Sent,
            s.finishWakeup(active.id, "sent", now).aiWakeups.first { it.id == active.id }.status,
        )
        assertEquals(
            ProactiveWakeupStatus.Failed,
            s.finishWakeup(active.id, "failed", now).aiWakeups.first { it.id == active.id }.status,
        )
        assertEquals(
            next,
            s.finishWakeup(active.id, "sent", now).aiWakeups.first { it.id == next.id },
        )
    }

    @Test
    fun `busy conversation or quiet window preserves the appointment with a short defer`() {
        val active = wakeup().copy(status = ProactiveWakeupStatus.Running)
        val s = setting(active).finishWakeup(active.id, "generation_busy", now)
        assertEquals(ProactiveWakeupStatus.Pending, s.aiWakeups.single().status)
        assertEquals(now + 60_000, s.aiWakeups.single().notBeforeMillis)
        assertNull(claimAiProactiveWakeup(s, active.id, now, zone).wakeup)
    }

    @Test
    fun `history trimming never discards a pending appointment`() {
        val pending = wakeup()
        val list =
            listOf(pending) +
                (1..20).map { wakeup("$it").copy(status = ProactiveWakeupStatus.Sent) }
        val trimmed = list.trimWakeupHistory()
        assertEquals(11, trimmed.size)
        assertTrue(pending in trimmed)
    }

    @Test
    fun `old settings remain opted out and wakeup data round trips`() {
        val old =
            kotlinx.serialization.json.Json.decodeFromString<ProactiveMessageSetting>(
                """{"enabled":true}"""
            )
        assertFalse(old.allowAiWakeups)
        assertTrue(old.aiWakeups.isEmpty())
        val chosen = setting(wakeup())
        val encoded =
            kotlinx.serialization.json.Json.encodeToString(
                ProactiveMessageSetting.serializer(),
                chosen,
            )
        assertEquals(
            chosen,
            kotlinx.serialization.json.Json.decodeFromString<ProactiveMessageSetting>(encoded),
        )
    }

    @Test
    fun `restart records interrupted work without resending or disturbing a current process claim`() {
        val old =
            wakeup().copy(status = ProactiveWakeupStatus.Running, startedAtMillis = now - 1_000)
        val current =
            wakeup("current")
                .copy(status = ProactiveWakeupStatus.Running, startedAtMillis = now + 1)
        val future = wakeup("future", now + 60_000)
        val restored = setting(old, current, future).recoverWakeupsBefore(now)
        assertEquals(
            ProactiveWakeupStatus.Failed,
            restored.aiWakeups.first { it.id == old.id }.status,
        )
        assertEquals(current, restored.aiWakeups.first { it.id == current.id })
        assertEquals(future, restored.aiWakeups.first { it.id == future.id })
    }

    @Test
    fun `explicit appointment takes priority over an earlier random check and normal mode returns afterwards`() {
        val task = wakeup(due = now + 3_600_000)
        val s = setting(task)
        assertEquals(task.atMillis, nextProactiveTriggerTime(s, now, 5, zone))
        val done = s.copy(aiWakeups = listOf(task.copy(status = ProactiveWakeupStatus.Sent)))
        assertEquals(now + 300_000, nextProactiveTriggerTime(done, now, 5, zone))
    }
}
