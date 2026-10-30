package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkTagTransformerTest {
    @Test
    fun `stream preview and final save share parsing without changing original history`() {
        val message = UIMessage.assistant("<THINKING>first</THINKING>answer<think>second")
        val preview = transformThinkTags(listOf(message), finished = false).single()
        val saved = transformThinkTags(listOf(message), finished = true).single()
        val previewThinking = preview.parts.filterIsInstance<UIMessagePart.Reasoning>()
        val savedThinking = saved.parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(listOf("first", "second"), previewThinking.map { it.reasoning })
        assertEquals(listOf("first", "second"), savedThinking.map { it.reasoning })
        assertNotNull(previewThinking.first().finishedAt)
        assertNull(previewThinking.last().finishedAt)
        assertNotNull(savedThinking.last().finishedAt)
        assertNotEquals(previewThinking.first().createdAt, previewThinking.last().createdAt)
        assertEquals(previewThinking.map { it.createdAt }, savedThinking.map { it.createdAt })
        assertEquals("answer", saved.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals(
            "<THINKING>first</THINKING>answer<think>second",
            (message.parts.single() as UIMessagePart.Text).text,
        )
        assertEquals(saved, transformThinkTags(listOf(saved), finished = true).single())
    }

    @Test
    fun `user messages tool results and normal prose remain untouched`() {
        val user = UIMessage.user("<think>literal</think>")
        val normal = UIMessage.assistant("Let me think. My answer is yes.")
        val tool =
            UIMessage(
                role = me.rerere.ai.core.MessageRole.ASSISTANT,
                parts =
                    listOf(
                        UIMessagePart.Tool(
                            "call",
                            "search",
                            "{}",
                            output = listOf(UIMessagePart.Text("<think>data</think>")),
                        )
                    ),
            )
        assertEquals(
            listOf(user, normal, tool),
            transformThinkTags(listOf(user, normal, tool), finished = true),
        )
    }
}
