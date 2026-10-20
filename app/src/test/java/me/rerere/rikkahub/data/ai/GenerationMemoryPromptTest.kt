package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationMemoryPromptTest {
    private val memories = listOf(
        AssistantMemory(17, "first\n\"quoted\""),
        AssistantMemory(23, " second "),
    )

    @Test
    fun `disabled memory emits nothing for either scene`() {
        GenerationScene.entries.forEach { scene ->
            assertEquals("", buildGenerationMemoryPrompt(false, memories, scene))
            assertEquals("", buildGenerationMemoryPrompt(false, emptyList(), scene))
        }
    }

    @Test
    fun `empty memory retains chat section but omits proactive section`() {
        assertEquals("", buildGenerationMemoryPrompt(true, emptyList(), GenerationScene.PROACTIVE))
        assertEquals(
            "\n\n**Memories**\n" +
                "These are memories stored via the memory_tool that you can reference in future conversations.\n[]\n",
            buildGenerationMemoryPrompt(true, emptyList(), GenerationScene.CHAT),
        )
    }

    @Test
    fun `chat keeps heading newlines ordered ids and exact content`() {
        val actual = buildGenerationMemoryPrompt(true, memories, GenerationScene.CHAT)
        val prefix = "\n\n**Memories**\n" +
            "These are memories stored via the memory_tool that you can reference in future conversations.\n"
        assertTrue(actual.startsWith(prefix))
        assertTrue(actual.endsWith("\n"))
        val items = Json.parseToJsonElement(actual.removePrefix(prefix).trim()).jsonArray
        assertEquals(listOf("17", "23"), items.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(memories.map { it.content }, items.map { it.jsonObject.getValue("content").jsonPrimitive.content })
        assertFalse(actual.contains("## 记忆"))
    }

    @Test
    fun `proactive retains verbatim list format and insertion boundaries`() {
        val original = memories.toList()
        val section = buildGenerationMemoryPrompt(true, memories, GenerationScene.PROACTIVE)
        assertEquals("\n\n## 记忆\n- first\n\"quoted\"\n-  second \n", section)
        assertEquals("role\n\n## 记忆\n- first\n\"quoted\"\n-  second \nbackground", "role${section}background")
        assertEquals(original, memories)
    }
}
