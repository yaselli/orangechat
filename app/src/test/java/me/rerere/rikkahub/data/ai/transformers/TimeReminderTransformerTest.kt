/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeReminderTransformerTest {
    private fun message(role: MessageRole, text: String, createdAt: LocalDateTime) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = createdAt,
        finishedAt = createdAt,
    )

    private fun text(message: UIMessage): String = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }

    @Test
    fun `empty messages remain empty`() {
        assertTrue(
            applyExtraTimeContext(
                emptyList(),
                currentTimeEnabled = false,
                replyIntervalEnabled = false,
            ).isEmpty(),
        )
    }

    @Test
    fun `current time switch injects exact time without reply interval`() {
        val messages = listOf(
            message(MessageRole.USER, "Hello", LocalDateTime(2026, 8, 25, 10, 0)),
        )
        val result = applyExtraTimeContext(
            messages,
            currentTimeEnabled = true,
            replyIntervalEnabled = false,
            currentInstant = Instant.parse("2026-08-25T01:05:00Z"),
        )
        assertEquals(2, result.size)
        assertTrue(text(result[0]).contains("<time_context>"))
        assertFalse(text(result[0]).contains("<reply_interval>"))
        assertEquals("Hello", text(result[1]))
    }

    @Test
    fun `time disabled injects nothing`() {
        val messages = listOf(
            message(MessageRole.USER, "现在几点", LocalDateTime(2026, 8, 25, 10, 0)),
        )
        val result = applyExtraTimeContext(
            messages,
            currentTimeEnabled = false,
            replyIntervalEnabled = false,
        )
        assertEquals(messages, result)
        assertFalse(result.any { text(it).contains("<time_") })
    }

    @Test
    fun `reply interval works independently from current time`() {
        val messages = listOf(
            message(MessageRole.ASSISTANT, "去忙吧", LocalDateTime(2026, 8, 25, 8, 0)),
            message(MessageRole.USER, "回来啦", LocalDateTime(2026, 8, 25, 10, 0)),
        )
        val result = applyExtraTimeContext(
            messages,
            currentTimeEnabled = false,
            replyIntervalEnabled = true,
            thresholdSeconds = 3600,
        )
        assertEquals(3, result.size)
        assertEquals(1, result.count { text(it).contains("<reply_interval>") })
        assertTrue(result.any { text(it).contains("2小时0分0秒") })
        assertFalse(result.any { text(it).contains("<time_context>") })
    }

    @Test
    fun `short reply gap does not add interval marker`() {
        val messages = listOf(
            message(MessageRole.ASSISTANT, "一会儿见", LocalDateTime(2026, 8, 25, 8, 0)),
            message(MessageRole.USER, "好", LocalDateTime(2026, 8, 25, 8, 5)),
        )
        val result = applyExtraTimeContext(
            messages,
            currentTimeEnabled = true,
            replyIntervalEnabled = true,
            thresholdSeconds = 3600,
            currentInstant = Instant.parse("2026-08-25T01:05:00Z"),
        )
        assertEquals(3, result.size)
        assertFalse(result.any { text(it).contains("<reply_interval>") })
    }
}
