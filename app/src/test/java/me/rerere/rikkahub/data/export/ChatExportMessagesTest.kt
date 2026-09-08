package me.rerere.rikkahub.data.export

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.extraInfoMessagePart
import org.junit.Assert.*
import org.junit.Test

class ChatExportMessagesTest {
    @Test fun hidesInternalContextWithoutChangingHistoryOrAttachments() {
        val image = UIMessagePart.Image(url = "https://example.com/sticker.png")
        val hidden = extraInfoMessagePart("<extra_info_context>private context</extra_info_context>")
        val original = UIMessage.user("hello").let { it.copy(parts = it.parts + image + hidden) }
        val reply = UIMessage.assistant("hi")
        val exported = prepareChatExportMessages(listOf(original, reply))
        assertEquals(listOf(UIMessagePart.Text("hello"), image), exported.first().parts)
        assertEquals(original.id, exported.first().id)
        assertSame(reply, exported.last())
        assertEquals(3, original.parts.size)
        assertEquals(hidden, original.parts.last())
    }

    @Test fun removesInjectionOnlyMessagesWithoutLeavingAnEmptyBubble() {
        val hidden = UIMessage.user("").copy(parts = listOf(extraInfoMessagePart("private context")))
        val reply = UIMessage.assistant("hi")
        assertEquals(listOf(reply), prepareChatExportMessages(listOf(hidden, reply)))
    }

    @Test fun preservesLiteralTagsWrittenByTheUser() {
        val message = UIMessage.user("Please explain <extra_info_context>example</extra_info_context>")
        assertSame(message, prepareChatExportMessages(listOf(message)).single())
        assertTrue(prepareChatExportMessages(emptyList()).isEmpty())
    }
}
