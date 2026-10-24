package me.rerere.rikkahub.data.interaction

import org.junit.Assert.*
import org.junit.Test

class InteractionTextTest {
    @Test
    fun `custom action and suffix remain independent and punctuation is user controlled`() {
        assertEquals("哥哥揉了揉你的脑袋，并递来牛奶", interactionText("哥哥", "你", "揉了揉", "的脑袋，并递来牛奶"))
        assertEquals("你戳了戳哥哥", interactionText("你", "哥哥", "戳了戳", ""))
        assertEquals("你拍了拍自己", interactionText("你", "自己", "拍了拍", ""))
    }

    @Test
    fun `oversized or empty actions cannot become persisted notices`() {
        for ((action, suffix) in listOf("" to "", "x".repeat(21) to "", "拍了拍" to "x".repeat(81))) {
            try {
                interactionText("你", "AI", action, suffix)
                fail("Expected validation")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun `reaction identity isolates conversation message actor emoji and exact sentence`() {
        val base = reactionKey("chat", "message", "user", "❤️", "喜欢牛奶")
        assertEquals(base, reactionKey("chat", "message", "user", "❤️", "喜欢牛奶"))
        assertNotEquals(base, reactionKey("other", "message", "user", "❤️", "喜欢牛奶"))
        assertNotEquals(base, reactionKey("chat", "other", "user", "❤️", "喜欢牛奶"))
        assertNotEquals(base, reactionKey("chat", "message", "assistant", "❤️", "喜欢牛奶"))
        assertNotEquals(base, reactionKey("chat", "message", "user", "👎", "喜欢牛奶"))
        assertNotEquals(base, reactionKey("chat", "message", "user", "❤️", "不喜欢牛奶"))
    }
}
