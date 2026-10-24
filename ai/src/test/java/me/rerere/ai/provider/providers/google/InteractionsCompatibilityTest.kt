package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import me.rerere.ai.core.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.ai.util.json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class InteractionsCompatibilityTest {
    private val api = InteractionsAPI(OkHttpClient())
    private val model = Model(modelId = "gemini-3.8-flash", abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING))
    private fun decode(vararg events: String): Pair<InteractionState, UIMessage> {
        val state = InteractionState(model.modelId)
        var messages = listOf(UIMessage.user("question"))
        events.forEach { event -> state.accept(null, event)?.let { messages = messages.handleMessageChunk(it) } }
        return state to messages.last()
    }
    private val textEvents = arrayOf(
        """{"event_type":"interaction.created","interaction":{"id":"i","model":"gemini-3.8-flash"}}""",
        """{"event_type":"step.start","index":0,"step":{"type":"thought"}}""",
        """{"event_type":"step.delta","index":0,"delta":{"type":"thought_summary","content":{"type":"text","text":"thinking"}}}""",
        """{"event_type":"step.delta","index":0,"delta":{"type":"thought_signature","signature":"signed"}}""",
        """{"event_type":"step.stop","index":0}""",
        """{"event_type":"step.start","index":1,"step":{"type":"model_output"}}""",
        """{"event_type":"step.delta","index":1,"delta":{"type":"text","text":"hello "}}""",
        """{"event_type":"step.delta","index":1,"delta":{"type":"text","text":"world"}}""",
        """{"event_type":"interaction.completed","interaction":{"id":"i","status":"completed","usage":{"total_input_tokens":10,"total_output_tokens":5,"total_thought_tokens":3,"total_cached_tokens":2,"total_tokens":18}}}""",
    )

    @Test fun requestIsStatelessAndFlatAndRetainsBothSystemPrompts() {
        val function = Tool("get_time", "time", execute = { listOf(UIMessagePart.Text("ok")) })
        val body = api.buildRequestBody(listOf(UIMessage.system("persona"), UIMessage.system("memory"), UIMessage.user("hi")),
            TextGenerationParams(model.copy(tools = setOf(BuiltInTools.Search)), tools = listOf(function),
                temperature = 0.4f, maxTokens = 1000), true)
        assertEquals(false, body["store"]!!.jsonPrimitive.boolean)
        assertEquals("persona\nmemory", body["system_instruction"]!!.jsonPrimitive.content)
        assertEquals(listOf("function", "google_search"), body["tools"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("object", body["tools"]!!.jsonArray.first().jsonObject["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("previous_interaction_id"))
        assertFalse(body.containsKey("safety_settings"))
    }

    @Test fun stepSnapshotsDoNotDuplicateTextAndReasoningSignatureSurvives() {
        val (state, message) = decode(*textEvents)
        assertTrue(state.completed)
        assertEquals("hello world", message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        val reasoning = message.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("thinking", reasoning.reasoning)
        assertEquals("signed", signature(reasoning.metadata))
        assertNotNull(reasoning.finishedAt)
        assertEquals(2, message.parts.size)
    }

    @Test fun functionArgumentsAreAssembledOnceAndServerToolsNeverBecomePhoneCalls() {
        val (state, message) = decode(
            """{"event_type":"step.start","index":0,"step":{"id":"s","type":"google_search_call","arguments":{"queries":["test"]}}}""",
            """{"event_type":"step.start","index":1,"step":{"call_id":"s","type":"google_search_result","result":[]}}""",
            """{"event_type":"step.start","index":2,"step":{"id":"phone","type":"function_call","name":"get_time"}}""",
            """{"event_type":"step.delta","index":2,"delta":{"type":"arguments_delta","arguments":"{\"zone\":"}}""",
            """{"event_type":"step.delta","index":2,"delta":{"type":"arguments_delta","arguments":"\"UTC\"}"}}""",
            """{"event_type":"interaction.completed","interaction":{"status":"requires_action"}}""",
        )
        assertTrue(state.completed)
        val tools = message.parts.filterIsInstance<UIMessagePart.Tool>()
        assertTrue(tools[0].isExecuted)
        assertFalse(tools[1].isExecuted)
        assertEquals("{\"zone\":\"UTC\"}", tools[1].input)
        val replied = message.copy(parts = listOf(tools[0], tools[1].copy(output = listOf(UIMessagePart.Text("time")))))
        val types = api.buildInput(listOf(replied)).map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("google_search_call", "google_search_result", "function_call", "function_result"), types)
    }

    @Test fun parallelPhoneCallsPrecedeTheirResultsAndOtherProtocolThoughtsAreNotReplayed() {
        val tools = listOf("one", "two").map { UIMessagePart.Tool(it, "tool_$it", "{}", listOf(UIMessagePart.Text("done"))) }
        val input = api.buildInput(listOf(UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("foreign", metadata = buildJsonObject { put("thoughtSignature", "foreign") })) + tools)))
        assertEquals(listOf("function_call", "function_call", "function_result", "function_result"),
            input.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertFalse(input.toString().contains("foreign"))
    }

    @Test fun finalSnapshotsReplaceProvisionalIndicesAndImageDataUrisStayIntact() {
        val state = InteractionState(model.modelId)
        state.accept(null, """{"event_type":"step.start","index":8,"step":{"type":"model_output","content":[{"type":"text","text":"provisional"}]}}""")
        val chunk = state.complete(json.parseToJsonElement("""{"steps":[{"type":"model_output","content":[{"type":"image","mime_type":"image/jpeg","data":"ABC"}]}],"status":"completed"}""").jsonObject)
        val result = listOf(UIMessage.user("hi")).handleMessageChunk(chunk).last()
        assertEquals("data:image/jpeg;base64,ABC", result.parts.filterIsInstance<UIMessagePart.Image>().single().url)
        assertEquals(1, result.parts.size)
    }

    private fun client(body: String, contentType: String) = OkHttpClient.Builder().addInterceptor { chain ->
        assertEquals("/v1beta/interactions", chain.request().url.encodedPath)
        assertEquals("test-key", chain.request().header("x-goog-api-key"))
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", contentType).body(body.toResponseBody(contentType.toMediaType())).build()
    }.build()

    @Test fun streamingHttpPathTerminatesAtCompletedAndReportsUsageWithoutRealRequests() = runBlocking {
        val sse = textEvents.joinToString("") { "data: $it\n\n" }
        val api = InteractionsAPI(client(sse, "text/event-stream"))
        val chunks = api.streamText(ProviderSetting.Google(apiKey = "test-key"), listOf(UIMessage.user("hi")), TextGenerationParams(model)).toList()
        assertEquals(TokenUsage(10, 8, 2, 18), chunks.last().usage)
        assertEquals("completed", chunks.last().choices.single().finishReason)
    }

    @Test fun truncatedHttpStreamsFailInsteadOfCompletingPartialToolCalls() {
        val api = InteractionsAPI(client("data: ${textEvents.first()}\n\n", "text/event-stream"))
        assertThrows(IllegalStateException::class.java) { runBlocking {
            api.streamText(ProviderSetting.Google(apiKey = "test-key"), listOf(UIMessage.user("hi")), TextGenerationParams(model)).toList()
        } }
    }
}
