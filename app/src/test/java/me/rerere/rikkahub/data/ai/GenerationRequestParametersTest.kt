package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.Assistant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class GenerationRequestParametersTest {
    @Test
    fun `unspecified sampling parameters stay absent`() {
        val params = buildGenerationRequestParameters(Assistant(), Model(), emptyList())
        assertNull(params.temperature)
        assertNull(params.topP)
        assertNull(params.maxTokens)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertTrue(params.tools.isEmpty())
    }

    @Test
    fun `explicit values and duplicate overrides keep their original order`() {
        val assistant = Assistant(
            temperature = 0f,
            topP = 0.8f,
            maxTokens = 2048,
            reasoningLevel = ReasoningLevel.OFF,
            customHeaders = listOf(CustomHeader("X-Test", "assistant")),
            customBodies = listOf(CustomBody("option", JsonPrimitive("assistant"))),
        )
        val model = Model(
            customHeaders = listOf(CustomHeader("X-Test", "model")),
            customBodies = listOf(CustomBody("option", JsonPrimitive("model"))),
        )
        val params = buildGenerationRequestParameters(assistant, model, emptyList())
        assertSame(model, params.model)
        assertEquals(assistant.temperature, params.temperature)
        assertEquals(assistant.topP, params.topP)
        assertEquals(assistant.maxTokens, params.maxTokens)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(listOf("assistant", "model"), params.customHeaders.map { it.value })
        assertEquals(listOf(JsonPrimitive("assistant"), JsonPrimitive("model")), params.customBody.map { it.value })
    }

    @Test
    fun `MCP schemas and tool permissions pass through without rebuilding`() {
        var schemaReads = 0
        val schema = InputSchema.Obj(
            properties = buildJsonObject {
                put("draft", buildJsonObject { put("type", "string") })
            },
        )
        val tool = Tool(
            name = "memory_draft",
            description = "Test tool",
            needsApproval = true,
            parameters = { schemaReads++; schema },
            execute = { error("The parameter builder must not execute tools") },
        )
        val tools = listOf(tool)
        val params = buildGenerationRequestParameters(Assistant(), Model(modelId = "kimi-k3"), tools)
        assertSame(tools, params.tools)
        assertSame(tool, params.tools.single())
        assertTrue(params.tools.single().needsApproval)
        assertEquals(0, schemaReads)
        assertSame(schema, params.tools.single().parameters())
    }
}
