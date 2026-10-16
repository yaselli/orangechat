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
    fun `chunks fold sequentially into one summary instead of parallel summaries as fake user turns`() = runBlocking {
        val original = conversation(listOf(UIMessage.user("a".repeat(30_000)), UIMessage.assistant("b".repeat(30_000))))
        val plan = original.planCompaction(0)
        var calls = 0
        val summary = compressContext(plan) { previous, _ ->
            calls++
            assertEquals(if (calls == 1) null else "summary 1", previous)
            "summary $calls"
        }
        assertEquals(2, calls)
        assertEquals("summary 2", summary)
        assertEquals(MessageRole.SYSTEM, contextSummaryMessage(summary).role)
        assertEquals(2, original.messageNodes.size)
    }

    @Test
    fun `oversized source and call limit are rejected before first model invocation`() = runBlocking {
        val oversized = conversation(listOf(UIMessage.user("a".repeat(50_000)), UIMessage.assistant("answer")))
        val tooMany = conversation((1..10).map { UIMessage.assistant("a".repeat(30_000)) })
        for (source in listOf(oversized, tooMany)) {
            var calls = 0
            try {
                compressContext(source.planCompaction(0)) { _, _ -> calls++; "summary" }
                fail("Must reject excess cost")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, calls)
        }
    }

    @Test
    fun `tool evidence is retained but internal reasoning and audio interpretation are not invented`() {
        val message = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("call", "lookup", "{\"q\":\"fact\"}", output = listOf(UIMessagePart.Text("evidence"))),
            UIMessagePart.Reasoning("private speculation"),
            UIMessagePart.Audio("file:///recording.wav"),
        ))
        val content = compactChunks(listOf(message)).single()
        assertTrue(content.contains("lookup"))
        assertTrue(content.contains("evidence"))
        assertFalse(content.contains("private speculation"))
        assertTrue(content.contains("not transcribed"))
    }

    @Test
    fun `empty output failed later chunk and cancellation never alter original nodes`() = runBlocking {
        val original = conversation(listOf(UIMessage.user("a".repeat(30_000)), UIMessage.assistant("b".repeat(30_000))))
        val plan = original.planCompaction(0)
        for (failure in listOf(IllegalStateException("failed"), CancellationException("cancelled"))) {
            var calls = 0
            try {
                compressContext(plan) { _, _ ->
                    calls++
                    if (calls == 2) throw failure
                    "temporary summary"
                }
                fail("Must propagate")
            } catch (actual: Exception) { assertSame(failure, actual) }
            assertNull(original.contextCompaction)
            assertEquals(2, original.messageNodes.size)
        }
        try {
            compressContext(plan) { _, _ -> " " }
            fail("Blank output must fail")
        } catch (_: IllegalArgumentException) { }
    }
}
