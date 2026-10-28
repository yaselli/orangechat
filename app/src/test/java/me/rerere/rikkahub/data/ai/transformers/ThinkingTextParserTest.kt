package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThinkingTextParserTest {
    @Test
    fun `every thinking block and intervening answer stays in its original order`() {
        assertEquals(
            listOf(
                ThinkingTextSegment("first", reasoning = true),
                ThinkingTextSegment("answer one"),
                ThinkingTextSegment("second", reasoning = true),
                ThinkingTextSegment("answer two"),
            ),
            splitThinkingText("<think>first</think>answer one<think>second</think>answer two"),
        )
    }

    @Test
    fun `explicit tag variants are case insensitive and allow trailing whitespace`() {
        for (tag in listOf("think", "THINK", "thinking", "Thinking")) {
            assertEquals(
                listOf(
                    ThinkingTextSegment("reason", reasoning = true),
                    ThinkingTextSegment("answer"),
                ),
                splitThinkingText("<$tag >reason</$tag >answer"),
            )
        }
    }

    @Test
    fun `unfinished stream is reasoning only after an explicit opening tag`() {
        assertEquals(
            listOf(ThinkingTextSegment("ongoing", reasoning = true, closed = false)),
            splitThinkingText("<think>ongoing"),
        )
        assertEquals(
            listOf(ThinkingTextSegment("", reasoning = true, closed = false)),
            splitThinkingText("<think>"),
        )
        assertEquals(
            listOf(ThinkingTextSegment("Let me think. Answer.")),
            splitThinkingText("Let me think. Answer."),
        )
        assertFalse(splitThinkingText("unmarked text</think>answer").any { it.reasoning })
    }

    @Test
    fun `literal tags in inline code and code fences remain answer text`() {
        for (literal in
            listOf(
                "Use `<think>literal</think>` in your example.",
                "```xml\n<think>literal</think>\n```",
                "~~~xml\n<thinking>literal</thinking>\n~~~",
                "```xml\n<think>still code without a closing fence",
            )) assertEquals(listOf(ThinkingTextSegment(literal)), splitThinkingText(literal))
        assertEquals(
            listOf(
                ThinkingTextSegment("`<think>example</think>`\n"),
                ThinkingTextSegment("real", reasoning = true),
                ThinkingTextSegment("answer"),
            ),
            splitThinkingText("`<think>example</think>`\n<think>real</think>answer"),
        )
    }

    @Test
    fun `code within thinking is preserved and whitespace is not trimmed`() {
        assertEquals(
            listOf(
                ThinkingTextSegment("  `code`\n", reasoning = true),
                ThinkingTextSegment("\n  answer"),
            ),
            splitThinkingText("<think>  `code`\n</think>\n  answer"),
        )
    }

    @Test
    fun `unknown tags and ordinary text are not reclassified`() {
        for (text in
            listOf(
                "",
                "normal answer",
                "<analysis>ordinary example</analysis>",
                "<thinker>x</thinker>",
            )) {
            assertEquals(listOf(ThinkingTextSegment(text)), splitThinkingText(text))
        }
    }
}
