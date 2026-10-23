package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class OpenAIRelayCompatibilityTest {
    private val api = ResponseAPI(OkHttpClient())
    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject
    private fun event(type: String, item: String) = obj("""{"type":"$type","item":$item}""")
    private val call = """{"type":"function_call","id":"fc_1","call_id":"call_1","name":"search","arguments":"{\"q\":\"Kotlin\"}"}"""
    private val result = """{"type":"function_call_output","call_id":"call_1","output":"found"}"""

    @Test
    fun `non-object keepalives are skipped and multiline JSON remains one payload`() {
        for (line in listOf("null", "[]", "42", "\"ping\"", " ")) assertNull(parseOpenAIStreamObject(line))
        assertEquals(obj("""{"id":"r1","choices":[]}"""), parseOpenAIStreamObject("{\n\"id\":\"r1\",\n\"choices\":[]\n}"))
    }

    @Test
    fun `malformed JSON remains an error`() {
        assertTrue(runCatching { parseOpenAIStreamObject("{\"choices\":[") }.isFailure)
    }

    @Test
    fun `nullable citations and tool array entries do not break valid text or reasoning`() {
        val chat = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val message = chat.parseMessage(obj("""{
            "content":"Hi", "reasoning_content":"Thinking", "tool_calls":[null],
            "annotations":[null,{"type":"url_citation","url_citation":null}]
        }"""))
        assertEquals("Hi", message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals("Thinking", message.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals(1, message.annotations.size)
        assertTrue(message.getTools().isEmpty())
    }

    @Test
    fun `non-streaming outputs match call IDs even when returned before calls`() {
        val chunk = api.parseResponseOutput(obj("""{"output":[$result,$call],"usage":null}"""))
        val tool = chunk.choices.single().message!!.getTools().single()
        assertEquals("call_1", tool.toolCallId)
        assertTrue(tool.isExecuted)
        assertEquals("found", (tool.output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `streaming snapshots resolve item IDs without duplicating arguments or executing twice`() {
        val state = ResponseToolStreamState()
        var message = UIMessage.assistant("")
        val events = listOf(
            event("response.output_item.added", call),
            obj("""{"type":"response.function_call_arguments.done","item_id":"fc_1","arguments":"{\"q\":\"Kotlin\"}"}"""),
            event("response.output_item.done", call),
            event("response.output_item.added", result),
            event("response.output_item.done", result),
            event("response.output_item.done", result),
            obj("""{"type":"response.completed","response":{"output":[$result],"usage":null}}"""),
        )
        events.forEach { event -> api.parseResponseDelta(event, state)?.let { message += it } }
        val tool = message.getTools().single()
        assertEquals("call_1", tool.toolCallId)
        assertEquals("search", tool.toolName)
        assertEquals("""{"q":"Kotlin"}""", tool.input)
        assertTrue(tool.isExecuted)
        assertEquals("found", (tool.output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `missing optional item ID resolves later argument events through output index`() {
        val state = ResponseToolStreamState()
        val noId = call.replace("\"id\":\"fc_1\",", "")
        val events = listOf(
            obj("""{"type":"response.output_item.added","output_index":3,"item":$noId}"""),
            obj("""{"type":"response.function_call_arguments.done","item_id":"fc_1","output_index":3,"arguments":"{\"q\":\"Kotlin\"}"}"""),
            event("response.output_item.done", call),
            event("response.output_item.done", result),
        )
        val message = events.fold(UIMessage.assistant("")) { message, event ->
            api.parseResponseDelta(event, state)?.let { message + it } ?: message
        }
        val tool = message.getTools().single()
        assertEquals("call_1", tool.toolCallId)
        assertEquals("""{"q":"Kotlin"}""", tool.input)
        assertTrue(tool.isExecuted)
    }

    @Test
    fun `client tools remain pending without a server result and request state is isolated`() {
        val first = ResponseToolStreamState()
        val second = ResponseToolStreamState()
        val tool = api.parseResponseDelta(event("response.output_item.added", call), first)!!
            .choices.single().delta!!.getTools().single()
        assertFalse(tool.isExecuted)
        assertEquals("fc_1", second.callId("fc_1"))
        assertEquals("call_1", first.callId("fc_1"))
    }

    @Test
    fun `completed response can supply tool result without separate output events`() {
        val state = ResponseToolStreamState()
        var message = UIMessage.assistant("") + api.parseResponseDelta(event("response.output_item.added", call), state)!!
        message += api.parseResponseDelta(obj("""{"type":"response.completed","response":{"output":[$result]}}"""), state)!!
        assertTrue(message.getTools().single().isExecuted)
    }

    @Test
    fun `output text preserves arrays objects and empty successful results`() {
        assertEquals("one\ntwo", obj("""{"output":[null,{"type":"input_text","text":"one"},{"type":"input_text","text":"two"}]}""").functionCallOutputText())
        assertEquals("{\"ok\":true}", obj("""{"output":{"ok":true}}""").functionCallOutputText())
        assertEquals("", obj("""{"output":null}""").functionCallOutputText())
        val state = ResponseToolStreamState()
        val empty = result.replace("\"found\"", "\"\"")
        val chunk = api.parseResponseDelta(event("response.output_item.done", empty), state)!!
        assertTrue(chunk.choices.single().delta!!.getTools().single().isExecuted)
    }
}
