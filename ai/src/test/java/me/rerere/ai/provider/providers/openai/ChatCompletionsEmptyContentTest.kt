package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class ChatCompletionsEmptyContentTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(20)
    private val vision = Model(modelId = "kimi-k3", inputModalities = listOf(Modality.TEXT, Modality.IMAGE))

    private fun messages(input: List<UIMessage>, model: Model = vision): JsonArray {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages", List::class.java, Model::class.java)
        method.isAccessible = true
        return method.invoke(ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default()), input, model) as JsonArray
    }

    private fun assertNoEmptyContentBlocks(messages: JsonArray) {
        messages.forEach { value ->
            val message = value.jsonObject
            val content = message["content"]
            if (content is JsonArray) {
                assertTrue("Empty content array", content.isNotEmpty())
                content.forEach { part ->
                    if (part.jsonObject["type"]?.jsonPrimitive?.content == "text") {
                        assertTrue("Empty text block", part.jsonObject.getValue("text").jsonPrimitive.content.isNotBlank())
                    }
                }
            } else if (message["role"]?.jsonPrimitive?.content != "assistant") {
                assertTrue("Empty non-assistant content", (content as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true)
            }
        }
    }

    @Test
    fun emptyMixedTextBlocksAreRemovedWithoutTrimmingRealTextOrMutatingHistory() {
        val parts = listOf(UIMessagePart.Text(""), UIMessagePart.Text("  retain this indentation\n"),
            UIMessagePart.Text("\n\t"), UIMessagePart.Image("https://example.invalid/reference.png"), UIMessagePart.Text(""))
        val input = listOf(UIMessage(role = MessageRole.USER, parts = parts),
            UIMessage(role = MessageRole.ASSISTANT, parts = parts))
        val result = messages(input)
        assertNoEmptyContentBlocks(result)
        result.forEach {
            assertEquals("  retain this indentation\n", it.jsonObject["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals(2, it.jsonObject["content"]!!.jsonArray.size)
        }
        assertEquals(parts, input.first().parts)
        assertEquals("", (input.first().parts.first() as UIMessagePart.Text).text)
    }

    @Test
    fun anUnreadableHistoricalImageKeepsItsPositionAsAnExplicitUnavailableMarker() {
        val result = messages(listOf(UIMessage(role = MessageRole.USER,
            parts = listOf(UIMessagePart.Image("missing-image:old.png"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Image("missing-image:reply.png"))),
            UIMessage.user("new question")))
        assertNoEmptyContentBlocks(result)
        assertEquals(3, result.size)
        assertTrue(result[0].jsonObject["content"]!!.jsonPrimitive.content.contains("Image unavailable"))
        assertTrue(result[1].jsonObject["content"]!!.jsonPrimitive.content.contains("Image unavailable"))
        assertFalse(result.toString().contains("old.png"))
    }

    @Test
    fun modalityFilteringCannotLeaveAnEmptyUserMessageButDoesKeepItsValidCaption() {
        val result = messages(listOf(UIMessage(role = MessageRole.USER,
            parts = listOf(UIMessagePart.Image("https://example.invalid/old.png"))),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("caption"),
                UIMessagePart.Image("https://example.invalid/new.png")))), Model(modelId = "text-only"))
        assertNoEmptyContentBlocks(result)
        assertEquals(1, result.size)
        assertEquals("caption", result.single().jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun emptyAndImageOnlyToolResultsKeepEveryCallIdAndDoNotInventAnAssistantAnswer() {
        val tools = listOf(
            UIMessagePart.Tool("empty", "empty_tool", "{}", output = listOf(UIMessagePart.Text(""))),
            UIMessagePart.Tool("image", "image_tool", "{}", output = listOf(UIMessagePart.Image("missing-image:tool.png"))),
        )
        val input = listOf(UIMessage.user("run tools"), UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("original reasoning")) + tools), UIMessage.user("continue"))
        val result = messages(input)
        assertNoEmptyContentBlocks(result)
        val assistant = result.map { it.jsonObject }.single { it["tool_calls"] != null }
        assertEquals("", assistant["content"]!!.jsonPrimitive.content)
        assertEquals("original reasoning", assistant["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals(listOf("empty", "image"), assistant["tool_calls"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val replies = result.map { it.jsonObject }.filter { it["role"]!!.jsonPrimitive.content == "tool" }
        assertEquals(listOf("empty", "image"), replies.map { it["tool_call_id"]!!.jsonPrimitive.content })
        assertEquals(tools, input[1].parts.filterIsInstance<UIMessagePart.Tool>())
    }

    @Test
    fun validMultiblockContentRetainsItsExistingRequestShape() {
        val result = messages(listOf(UIMessage(role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("first"), UIMessagePart.Text("second")))))
        assertEquals(buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", "first") })
                    add(buildJsonObject { put("type", "text"); put("text", "second") })
                }
            })
        }, result)
    }

    @Test
    fun actualHttpRequestPreservesALongSyntheticHistoryAndPassesAStrictEmptyBlockValidator() = runBlocking {
        // 100,001 synthetic words, not a claim about an exact token count; no external HTTP request is made.
        val longText = "history ".repeat(100_001)
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val request = Json.parseToJsonElement(chain.request().body!!.let {
                val buffer = Buffer(); it.writeTo(buffer); buffer.readUtf8()
            }).jsonObject
            val sent = request["messages"]!!.jsonArray
            assertNoEmptyContentBlocks(sent)
            assertEquals(3, sent.size)
            assertEquals(longText, sent.first().jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("last user question", sent.last().jsonObject["content"]!!.jsonPrimitive.content)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"id":"test","model":"kimi-k3","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = ChatCompletionsAPI(client, KeyRoulette.default())
        val response = api.generateText(ProviderSetting.OpenAI(baseUrl = "https://api.moonshot.cn/v1", apiKey = "test-only"),
            listOf(UIMessage.user(longText), UIMessage(role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text(""), UIMessagePart.Text("answer"), UIMessagePart.Text(""))),
                UIMessage.user("last user question")), TextGenerationParams(vision))
        assertEquals(1, requests)
        assertEquals("ok", response.choices.single().message!!.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }
}
