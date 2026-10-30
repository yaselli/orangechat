package me.rerere.rikkahub.data.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryRecallQueryTest {
    @Test
    fun `new topics never import the preceding question`() {
        for (text in listOf("我想吃辣辣的", "那我想吃火锅呢", "明天天气怎么样", "你猜我吃了什么")) {
            assertEquals(text, buildMemoryRecallQuery(text, "先把快速启动关掉"))
        }
    }

    @Test
    fun `explicit short references retain preceding user context`() {
        assertEquals("这个呢？\n我喜欢炸鸡", buildMemoryRecallQuery("这个呢？", "我喜欢炸鸡"))
        assertEquals("然后呢", buildMemoryRecallQuery("然后呢", null))
        assertEquals("", buildMemoryRecallQuery("  ", "我喜欢炸鸡"))
    }

    @Test
    fun `query budget keeps current request ahead of bounded context`() {
        val query = buildMemoryRecallQuery("这个呢", "🍗".repeat(2000))
        assertTrue(query.startsWith("这个呢\n"))
        assertTrue(MemoryTextBudget().count(query) <= 2000)
        assertFalse(query.last().isHighSurrogate())
    }
}
