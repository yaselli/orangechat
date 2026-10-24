package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class RequestInputProfileTest {
    private fun profile(messages: List<UIMessage>) = RequestInputProfile(
        messages.size, inputTextBytes(messages), inputRoleSizes(messages),
        listOf(InputSizeEntry("角色/会话设定", 12)), listOf(InputSizeEntry("TemplateTransformer", -2)),
        300, 1, 800, listOf(InputSizeEntry("memory_tool", 800)), 0,
    )

    @Test
    fun `UTF8 counts Chinese and emoji correctly and role totals preserve text size`() {
        val messages = listOf(UIMessage.system("角色"), UIMessage.user("吃辣🌶"), UIMessage.assistant("好"))
        assertEquals(6L, inputUtf8Bytes("角色"))
        assertEquals(10L, inputUtf8Bytes("吃辣🌶"))
        assertEquals(19L, inputTextBytes(messages))
        assertEquals(inputTextBytes(messages), inputRoleSizes(messages).sumOf { it.bytes })
    }

    @Test
    fun `local metadata and attachments do not inflate measured prompt text`() {
        val user = UIMessage.user("hello").copy(
            localMetadata = buildJsonObject { put("private", "x".repeat(10_000)) },
            parts = listOf(UIMessagePart.Text("hello"), UIMessagePart.Image("data:image/png;base64," + "x".repeat(10_000))),
        )
        assertEquals(5L, inputTextBytes(listOf(user)))
    }

    @Test
    fun `profile round trip preserves other namespaces and never records prompt bodies`() {
        val messages = listOf(UIMessage.system("private role text"), UIMessage.user("private user text"))
        val reply = UIMessage.assistant("answer").copy(localMetadata = buildJsonObject { put("existing", "keep") })
        val recorded = reply.attachRequestInputProfile(profile(messages))
        assertEquals(profile(messages), recorded.requestInputProfile())
        assertEquals(reply.parts, recorded.parts)
        assertEquals(reply.localMetadata?.get("existing"), recorded.localMetadata?.get("existing"))
        assertFalse(recorded.localMetadata.toString().contains("private role text"))
        assertFalse(recorded.localMetadata.toString().contains("private user text"))
        assertEquals(-2L, recorded.requestInputProfile()!!.transformations.single().bytes)
        assertSame(messages.last(), messages.last().attachRequestInputProfile(profile(messages)))
        assertNull(reply.requestInputProfile())
    }

    @Test
    fun `tool input and text output contribute without storing their contents in the profile`() {
        val message = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool(
            toolCallId = "call", toolName = "test", input = "{}", output = listOf(UIMessagePart.Text("结果")),
        )))
        assertEquals(8L, inputTextBytes(listOf(message)))
    }
}
