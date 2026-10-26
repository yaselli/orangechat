package me.rerere.rikkahub.data.music

import org.junit.Assert.*
import org.junit.Test

class MusicCommentaryPolicyTest {
    private fun playing(now: Long, position: Long = now, songId: String = "1") =
        MusicPlayback(
            "role",
            "chat",
            MusicQueueItem("queue-$songId", songId),
            MusicSong(songId, lyrics = listOf(MusicLyric(0, 0, "a lyric"))),
            state = "playing",
            positionMs = position,
            observedAt = now,
        )

    @Test
    fun onlyListeningTimeCountsAndSeekingDoesNotTriggerImmediately() {
        val policy = MusicCommentaryPolicy()
        assertNull(policy.observe(playing(0, 150_000), true, 0))
        for (second in 1..44) assertNull(
            policy.observe(playing(second * 1000L, 150_000), true, second * 1000L)
        )
        assertNotNull(policy.observe(playing(45_000), true, 45_000))
        for (second in 46..500) assertNull(
            policy.observe(playing(second * 1000L), true, second * 1000L)
        )
    }

    @Test
    fun disabledPausedStaleAndTrialPlaybackNeverScheduleRequests() {
        for (mode in listOf("disabled", "paused", "stale", "trial", "missing-chat")) {
            val policy = MusicCommentaryPolicy()
            for (second in 0..90) {
                val now = second * 1000L
                var playback = playing(now)
                playback =
                    when (mode) {
                        "paused" -> playback.copy(state = "paused")
                        "stale" -> playback.copy(observedAt = -100_000)
                        "trial" -> playback.copy(trial = true)
                        "missing-chat" -> playback.copy(conversationId = "")
                        else -> playback
                    }
                assertNull(policy.observe(playback, mode != "disabled", now))
            }
        }
    }

    @Test
    fun anotherSongRespectsGlobalCooldownAndLoopsDoNotRepeat() {
        val policy = MusicCommentaryPolicy()
        for (second in 0..44) policy.observe(playing(second * 1000L), true, second * 1000L)
        assertNotNull(policy.observe(playing(45_000), true, 45_000))
        for (second in 46..344) assertNull(
            policy.observe(playing(second * 1000L, songId = "2"), true, second * 1000L)
        )
        assertNotNull(policy.observe(playing(345_000, songId = "2"), true, 345_000))
        for (second in 346..700) assertNull(
            policy.observe(playing(second * 1000L), true, second * 1000L)
        )
    }

    @Test
    fun changingPlaybackOrStoppingInvalidatesInFlightReply() {
        val cue = playing(45_000)
        assertTrue(playing(50_000).canDeliverCommentary(cue, 50_000))
        assertFalse(playing(50_000, songId = "2").canDeliverCommentary(cue, 50_000))
        assertFalse(playing(50_000).copy(assistantId = "other").canDeliverCommentary(cue, 50_000))
        assertFalse(
            playing(50_000).copy(conversationId = "other").canDeliverCommentary(cue, 50_000)
        )
        assertFalse(playing(50_000).copy(state = "paused").canDeliverCommentary(cue, 50_000))
        assertFalse(playing(50_000, position = 1_000).canDeliverCommentary(cue, 50_000))
    }

    @Test
    fun SilenceAndThinkingOnlyAreNotPublished() {
        assertNull(musicCommentaryReply("[PASS]"))
        assertNull(musicCommentaryReply("<think>private reasoning</think>"))
        assertNull(musicCommentaryReply("[WAIT]"))
        assertEquals("这句好温柔。", musicCommentaryReply("  这句好温柔。  "))
    }
}
