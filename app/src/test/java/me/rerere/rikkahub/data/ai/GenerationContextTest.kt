package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GenerationContextTest {
    private val assistant = Assistant(systemPrompt = "  role\n", allowConversationSystemPrompt = true)

    @Test
    fun `chat uses enabled nonblank conversation prompt verbatim`() {
        assertEquals("  conversation\n", selectGenerationSystemPrompt(
            assistant, GenerationScene.CHAT, "  conversation\n"
        ))
    }

    @Test
    fun `chat falls back for blank absent or disabled conversation prompt`() {
        listOf(null, "", " \n").forEach { prompt ->
            assertEquals(assistant.systemPrompt, selectGenerationSystemPrompt(
                assistant, GenerationScene.CHAT, prompt
            ))
        }
        assertEquals(assistant.systemPrompt, selectGenerationSystemPrompt(
            assistant.copy(allowConversationSystemPrompt = false), GenerationScene.CHAT, "override"
        ))
    }

    @Test
    fun `proactive keeps role prompt even with a conversation override`() {
        assertEquals(assistant.systemPrompt, selectGenerationSystemPrompt(
            assistant, GenerationScene.PROACTIVE, "override"
        ))
    }

    @Test
    fun `chat retains unlimited and bounded history behavior`() {
        val history = List(30) { UIMessage.user("message $it") }
        for (size in listOf(-1, 0, 40)) {
            assertSame(history, selectGenerationHistory(history, size, GenerationScene.CHAT))
        }
        assertEquals(history.takeLast(3), selectGenerationHistory(history, 3, GenerationScene.CHAT))
    }

    @Test
    fun `proactive keeps twenty message cap and smaller configured limits`() {
        val history = List(30) { UIMessage.user("message $it") }
        val original = history.toList()
        for (size in listOf(-1, 0, 20, 100)) {
            assertEquals(history.takeLast(20), selectGenerationHistory(history, size, GenerationScene.PROACTIVE))
        }
        val selected = selectGenerationHistory(history, 3, GenerationScene.PROACTIVE)
        assertEquals(history.takeLast(3), selected)
        assertSame(history.last(), selected.last())
        assertEquals(original, history)
        for (scene in GenerationScene.entries) {
            assertEquals(emptyList<UIMessage>(), selectGenerationHistory(emptyList(), 3, scene))
        }
    }

    @Test
    fun `chat extends history boundary to retain tool dependencies`() {
        val history = listOf(
            UIMessage.user("question"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool(toolCallId = "call1", toolName = "test", input = "{}", output = emptyList())
            )),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call1", toolName = "test", input = "{}",
                    output = listOf(UIMessagePart.Text("result"))
                )
            )),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("answer"))),
        )
        assertEquals(history, selectGenerationHistory(history, 2, GenerationScene.CHAT))
        // Preserve proactive's existing boundary; its caller handles protocol cleanup separately.
        assertEquals(history.takeLast(2), selectGenerationHistory(history, 2, GenerationScene.PROACTIVE))
    }
}
