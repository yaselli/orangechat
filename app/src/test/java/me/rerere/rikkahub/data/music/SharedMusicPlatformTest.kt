package me.rerere.rikkahub.data.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SharedMusicPlatformTest {
    @Test
    fun `old saved connections and song cards remain NetEase by default`() {
        val connection =
            Json.decodeFromString<MusicConnection>(
                """{"server":"https://music.example","token":"device"}"""
            )
        val song = Json.decodeFromString<MusicSong>("""{"songId":"123","title":"old song"}""")
        assertEquals("netease", connection.platform)
        assertEquals("netease", song.platform)
        assertTrue(song.cardPart().text.contains("music.163.com"))
    }

    @Test
    fun `QQ cards and queue items keep their platform when saved and shared`() {
        val song = MusicQueueItem("item", "123", title = "QQ song", platform = "qq").song()
        val card = song.cardPart()
        assertEquals("qq", song.platform)
        assertEquals("qq", card.musicCard()!!["platform"]?.jsonPrimitive?.contentOrNull)
        assertTrue(card.text.contains("https://i.y.qq.com/v8/playsong.html?songid=123"))
        assertFalse(card.text.contains("music.163.com"))
    }

    @Test
    fun `audio streams are HTTPS and belong to their selected platform`() {
        assertTrue(musicStreamAllowed("https://ws.stream.qqmusic.qq.com/song.mp3?vkey=test", "qq"))
        assertTrue(musicStreamAllowed("https://m8.music.126.net/song.mp3", "netease"))
        assertFalse(musicStreamAllowed("https://m8.music.126.net/song.mp3", "qq"))
        assertFalse(musicStreamAllowed("https://ws.stream.qqmusic.qq.com/song.mp3", "netease"))
        assertFalse(musicStreamAllowed("http://ws.stream.qqmusic.qq.com/song.mp3", "qq"))
        assertFalse(musicStreamAllowed("https://ws.stream.qqmusic.qq.com.evil.example/song", "qq"))
        assertFalse(musicStreamAllowed("https://user:secret@ws.stream.qqmusic.qq.com/song", "qq"))
        assertNotNull(musicCoverUrl("https://y.gtimg.cn/music/photo.jpg"))
        assertNull(musicCoverUrl("https://y.gtimg.cn.evil.example/photo.jpg"))
    }

    @Test
    fun `song IDs and share links cannot become arbitrary URLs`() {
        assertThrows(IllegalArgumentException::class.java) { musicShareUrl("qq", "../private") }
        assertThrows(IllegalStateException::class.java) { musicShareUrl("other", "123") }
    }
}
