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
        assertTrue(applyTimeReminder(emptyList()).isEmpty())
    }

    @Test
    fun `single user gets one current time context`() {
        val messages = listOf(
            message(MessageRole.USER, "Hello", LocalDateTime(2026, 8, 25, 10, 0)),
        )
        val result = applyTimeReminder(
            messages,
            currentInstant = Instant.parse("2026-08-25T01:05:00Z"),
        )
        assertEquals(2, result.size)
        assertTrue(text(result[0]).contains("<time_context>"))
        assertEquals("Hello", text(result[1]))
    }

    @Test
    fun `long reply gap is described from previous assistant finish`() {
        val messages = listOf(
            message(MessageRole.ASSISTANT, "去忙吧", LocalDateTime(2026, 8, 25, 8, 0)),
            message(MessageRole.USER, "回来啦", LocalDateTime(2026, 8, 25, 10, 0)),
        )
        val result = applyTimeReminder(
            messages,
            thresholdSeconds = 300,
            currentInstant = Instant.parse("2026-08-25T01:05:00Z"),
        )
        assertEquals(3, result.size)
        assertTrue(text(result[1]).contains("2小时0分0秒"))
    }

    @Test
    fun `only latest user receives time context`() {
        val messages = listOf(
            message(MessageRole.USER, "一", LocalDateTime(2026, 8, 25, 8, 0)),
            message(MessageRole.ASSISTANT, "二", LocalDateTime(2026, 8, 25, 8, 1)),
            message(MessageRole.USER, "三", LocalDateTime(2026, 8, 25, 10, 0)),
        )
        val result = applyTimeReminder(messages, currentInstant = Instant.parse("2026-08-25T01:05:00Z"))
        assertEquals(4, result.size)
        assertEquals(1, result.count { text(it).contains("<time_context>") })
        assertEquals("三", text(result.last()))
    }
}
