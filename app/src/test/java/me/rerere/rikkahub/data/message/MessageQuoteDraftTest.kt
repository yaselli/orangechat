package me.rerere.rikkahub.data.message

import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.hooks.ChatInputState
import org.junit.Assert.*
import org.junit.Test

class MessageQuoteDraftTest {
    private val quote = MessageQuote("source", "沈渡", "第二句")

    @Test
    fun sendingClearsTheQuoteAndTheNextMessageContainsOnlyTheNewReply() {
        val state = ChatInputState()
        state.setMessageText("打了一半的回复")
        state.quoteMessage(quote)
        val sent = state.getContents()
        assertEquals("打了一半的回复", (sent.last() as UIMessagePart.Text).text)
        assertEquals(quote, sent.first().messageQuote())
        state.clearInput()
        assertNull(state.quotedMessage)
        state.setMessageText("下一轮")
        assertEquals(listOf(UIMessagePart.Text("下一轮")), state.getContents())
    }

    @Test
    fun cancelReplaceAndEditingDoNotRestoreOrDuplicateAnOldQuote() {
        val state = ChatInputState()
        state.setContents(
            listOf(
                quote.toPart(),
                UIMessagePart.Image("file:///saved.png"),
                UIMessagePart.Text("旧回复"),
            )
        )
        state.editingMessage = Uuid.random()
        assertEquals("旧回复", state.textContent.text.toString())
        assertEquals(quote, state.quotedMessage)
        state.quoteMessage(quote.copy(text = "替换引用"))
        state.setMessageText("新回复")
        assertEquals(1, state.getContents().count { it.messageQuote() != null })
        state.clearQuote()
        val edited = state.getContents()
        assertFalse(edited.any { it.messageQuote() != null })
        assertEquals(
            listOf(UIMessagePart.Image("file:///saved.png"), UIMessagePart.Text("新回复")),
            edited,
        )
    }

    @Test
    fun directStickerOrVoiceSendConsumesTheQuoteWithoutErasingTypedDraftOrAttachments() {
        val state = ChatInputState()
        state.setMessageText("草稿")
        state.messageContent = listOf(UIMessagePart.Image("file:///draft.png"))
        state.quoteMessage(quote)
        assertEquals(quote, state.consumeQuote().single().messageQuote())
        assertNull(state.quotedMessage)
        assertTrue(state.consumeQuote().isEmpty())
        assertEquals("草稿", state.textContent.text.toString())
        assertEquals(listOf(UIMessagePart.Image("file:///draft.png")), state.messageContent)
    }
}
