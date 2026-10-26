package me.rerere.rikkahub.data.music

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.hooks.ChatInputState
import org.junit.Assert.*
import org.junit.Test

class SharedMusicDraftTest {
    private val card = MusicSong("123", "song", "artist").cardPart()

    @Test
    fun `sharing a card keeps it attached while the user writes a caption`() {
        val state = ChatInputState()
        state.messageContent = listOf(card)
        state.setMessageText("这首给你")
        val parts = state.getContents()
        assertEquals("这首给你", (parts.first() as UIMessagePart.Text).text)
        assertEquals(listOf(card), parts.filter { it.musicCard() != null })
    }

    @Test
    fun `editing caption does not duplicate a saved song card or turn it into the editable text`() {
        val state = ChatInputState()
        state.setContents(listOf(UIMessagePart.Text("old"), card))
        state.editingMessage = kotlin.uuid.Uuid.random()
        assertEquals("old", state.textContent.text.toString())
        state.setMessageText("new")
        assertEquals(listOf(UIMessagePart.Text("new"), card), state.getContents())
    }

    @Test
    fun `removing a song card while editing does not restore it on send`() {
        val state = ChatInputState()
        state.setContents(listOf(UIMessagePart.Text("caption"), card))
        state.editingMessage = kotlin.uuid.Uuid.random()
        state.messageContent = emptyList()
        assertEquals(listOf(UIMessagePart.Text("caption")), state.getContents())
    }
}
