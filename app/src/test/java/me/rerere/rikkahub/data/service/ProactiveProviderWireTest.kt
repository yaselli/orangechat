package me.rerere.rikkahub.data.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ProactiveProviderWireTest {
    private val model = Model(modelId = "gemini-3.8-flash")

    private fun wire(messages: List<UIMessage>, google: Boolean): JsonArray {
        val client = OkHttpClient()
        return if (google) {
            val provider = GoogleProvider(client)
            val method = GoogleProvider::class.java.getDeclaredMethod("buildContents", List::class.java)
            method.isAccessible = true
            method.invoke(provider, messages) as JsonArray
        } else {
            val provider = ChatCompletionsAPI(client, KeyRoulette.default())
            val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
                "buildMessages", List::class.java, Model::class.java,
            )
            method.isAccessible = true
            method.invoke(provider, messages, model) as JsonArray
        }
    }

    @Test
    fun `native and relay Gemini wire requests end in a nonempty user event`() {
        val history = listOf(UIMessage.user("later"), UIMessage.assistant("ok"))
        val request = ProactiveProviderRequest.create(history, model.modelId, false)!!.applyTo(history)
        for (google in listOf(true, false)) {
            assertEquals("user", wire(request, google).last().jsonObject["role"]!!.jsonPrimitive.content)
        }
        assertEquals(2, history.size)
    }

    @Test
    fun `completed tool result stays paired without an extra trailing user event`() {
        val history = listOf(UIMessage.user("later"), UIMessage.assistant("ok"))
        val helper = ProactiveProviderRequest.create(history, model.modelId, false)!!
        val toolTurn = toolTurn()
        val request = helper.applyTo(history + toolTurn)
        assertSame(toolTurn, request.last())
        val relay = wire(request, false).last().jsonObject
        assertEquals("tool", relay["role"]!!.jsonPrimitive.content)
        assertEquals("call_now", relay["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("user", wire(request, true).last().jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant text after tool output receives a request-only continuation bridge`() {
        val history = listOf(UIMessage.user("later"), UIMessage.assistant("ok"))
        val helper = ProactiveProviderRequest.create(history, model.modelId, false)!!
        val turn = toolTurn().let { it.copy(parts = it.parts + UIMessagePart.Text("Checking the result")) }
        val local = history + turn
        val request = helper.applyTo(local)
        assertEquals(MessageRole.USER, request.last().role)
        assertSame(turn, local.last())
        assertSame(request, helper.applyTo(request))
        for (google in listOf(true, false)) {
            assertEquals("user", wire(request, google).last().jsonObject["role"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `pending tool calls are never interrupted by a continuation bridge`() {
        val history = listOf(UIMessage.assistant("ok"))
        val helper = ProactiveProviderRequest.create(history, model.modelId, false)!!
        val turn = toolTurn().let { it.copy(parts = listOf(it.getTools().single().copy(output = emptyList()))) }
        assertSame(turn, helper.applyTo(history + turn).last())
    }

    private fun toolTurn() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool(
            toolCallId = "call_now", toolName = "lookup", input = "{}",
            output = listOf(UIMessagePart.Text("found")),
        )),
    )
}
