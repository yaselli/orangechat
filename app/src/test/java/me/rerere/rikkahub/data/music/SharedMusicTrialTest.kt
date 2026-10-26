package me.rerere.rikkahub.data.music

import org.junit.Assert.*
import org.junit.Test

class SharedMusicTrialTest {
    @Test
    fun `a preview is identified without claiming a full-song lyric position`() {
        val song =
            MusicSong("123", "song", "artist", lyrics = listOf(MusicLyric(0, 0, "full song lyric")))
        val playback =
            MusicPlayback(
                "role",
                "chat",
                MusicQueueItem("item", "123"),
                song,
                state = "playing",
                observedAt = 1000,
                trial = true,
            )
        val context = playback.contextFor("role", 1000)!!
        assertTrue(context.contains("\"trial\":true"))
        assertFalse(context.contains("full song lyric"))
    }
}
