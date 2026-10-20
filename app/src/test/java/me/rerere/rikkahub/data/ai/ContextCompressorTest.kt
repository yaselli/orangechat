package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.planCompaction
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ContextCompressorTest {
    private fun conversation(messages: List<UIMessage>) = Conversation(
        assistantId = Uuid.random(), messageNodes = messages.map { MessageNode.of(it) }
    )

    @Test
    fun `large conversation is summarized in one call with all source text and no truncation`() = runBlocking {
        val userText = "first\n" + "内容和空格 🥛 ".repeat(60_000) + "\nlast"
        val answer = "b".repeat(70_000)
        val original = conversation(listOf(UIMessage.user(userText), UIMessage.assistant(answer)))
        val plan = original.planCompaction(0)
        var calls = 0
        val summary = compressContext(plan) { previous, content ->
            calls++
            assertNull(previous)
            assertEquals("[USER]\n$userText\n\n[ASSISTANT]\n$answer", content)
            "  complete summary  "
        }
        assertEquals(1, calls)
        assertEquals("complete summary", summary)
        assertEquals(MessageRole.SYSTEM, contextSummaryMessage(summary).role)
        assertNull(original.contextCompaction)
        assertEquals(2, original.messageNodes.size)
        assertEquals(userText, (original.currentMessages[0].parts[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `previous summary and all new messages are passed together exactly once`() = runBlocking {
        val original = conversation(listOf(UIMessage.user("question"), UIMessage.assistant("answer")))
        val plan = original.planCompaction(0).copy(previousSummary = "existing summary")
        var calls = 0
        assertEquals("merged summary", compressContext(plan) { previous, content ->
            calls++
            assertEquals("existing summary", previous)
            assertEquals("[USER]\nquestion\n\n[ASSISTANT]\nanswer", content)
            "merged summary"
        })
        assertEquals(1, calls)
    }

    @Test
    fun `tool evidence is retained but internal reasoning and audio interpretation are not invented`() {
        val message = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("call", "lookup", "{\"q\":\"fact\"}", output = listOf(UIMessagePart.Text("evidence"))),
            UIMessagePart.Reasoning("private speculation"),
            UIMessagePart.Audio("file:///recording.wav"),
        ))
        val content = compactContent(listOf(message))
        assertTrue(content.contains("lookup"))
        assertTrue(content.contains("evidence"))
        assertFalse(content.contains("private speculation"))
        assertTrue(content.contains("not transcribed"))
    }

    @Test
    fun `model failure and cancellation are not retried and never alter original nodes`() = runBlocking {
        val original = conversation(listOf(UIMessage.user("question"), UIMessage.assistant("answer")))
        val plan = original.planCompaction(0)
        for (failure in listOf(IllegalStateException("context limit"), CancellationException("cancelled"))) {
            var calls = 0
            try {
                compressContext(plan) { _, _ ->
                    calls++
                    throw failure
                }
                fail("Must propagate without fallback calls")
            } catch (actual: Exception) { assertSame(failure, actual) }
            assertEquals(1, calls)
            assertNull(original.contextCompaction)
            assertEquals(2, original.messageNodes.size)
        }
    }

    @Test
    fun `blank summary is rejected without retry and empty source makes no model call`() = runBlocking {
        val original = conversation(listOf(UIMessage.user("question"), UIMessage.assistant("answer")))
        val plan = original.planCompaction(0)
        var calls = 0
        try {
            compressContext(plan) { _, _ -> calls++; " " }
            fail("Blank output must fail")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, calls)
        assertNull(original.contextCompaction)
        try {
            compressContext(plan.copy(newMessages = emptyList())) { _, _ -> calls++; "unexpected" }
            fail("Empty input must fail")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, calls)
    }
}
