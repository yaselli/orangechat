package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class GenerationToolExecutionTest {
    private val json = Json
    private fun call(input: String = "{}") = UIMessagePart.Tool("call_1", "example", input)

    @Test
    fun `successful result preserves call identity and output`() = runBlocking {
        val input = call()
        val output = listOf(UIMessagePart.Text("done"))
        val result = executeGenerationTool(input, Tool("example", "", execute = { output }), json)
        assertEquals(input.copy(output = output), result)
    }

    @Test
    fun `empty success is completed and retains its call id`() = runBlocking {
        val result = executeGenerationTool(call(), Tool("example", "", execute = { emptyList() }), json)
        assertTrue(result.isExecuted)
        assertEquals("call_1", result.toolCallId)
    }

    @Test
    fun `broken arguments do not execute tool`() = runBlocking {
        var invoked = false
        val result = executeGenerationTool(call("{broken"), Tool("example", "", execute = {
            invoked = true
            emptyList()
        }), json)
        assertFalse(invoked)
        assertTrue(result.isExecuted)
        assertTrue(error(result).contains("Invalid tool arguments"))
    }

    @Test
    fun `missing tool returns paired failure`() = runBlocking {
        val result = executeGenerationTool(call(), null, json)
        assertEquals("call_1", result.toolCallId)
        assertTrue(error(result).contains("not found"))
    }

    @Test
    fun `failure with quotes and newline remains valid JSON`() = runBlocking {
        val result = executeGenerationTool(call(), Tool("example", "", execute = {
            throw IllegalStateException("quoted \"value\"\nnext line")
        }), json)
        assertTrue(result.isExecuted)
        assertTrue(error(result).contains("quoted \"value\"\nnext line"))
        assertEquals("call_1", result.toolCallId)
    }

    @Test
    fun `cancellation propagates instead of becoming a tool result`() = runBlocking {
        val cancellation = CancellationException("stop")
        try {
            executeGenerationTool(call(), Tool("example", "", execute = { throw cancellation }), json)
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private fun error(result: UIMessagePart.Tool): String = json.parseToJsonElement(
        (result.output.single() as UIMessagePart.Text).text
    ).jsonObject.getValue("error").jsonPrimitive.content
}
