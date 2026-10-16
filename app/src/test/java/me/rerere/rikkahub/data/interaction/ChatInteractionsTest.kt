package me.rerere.rikkahub.data.interaction

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class ChatInteractionsTest {
    @Test
    fun `only typed assistant notices are removed from provider system instructions`() {
        val user = UIMessage.user("普通文字：拍了拍")
        val notice = UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text("AI拍了拍你")),
            annotations = listOf(UIMessageAnnotation.AvatarInteraction("assistant", "user")))
        val userNotice = user.copy(annotations = listOf(UIMessageAnnotation.AvatarInteraction("user", "assistant")))
        val system = UIMessage.system("Actual persona")
        assertEquals(listOf(system, user, userNotice),
            listOf(system, user, notice, userNotice).withoutAssistantInteractionNotices())
        assertNull(user.avatarInteraction())
    }

    @Test
    fun `annotation survives export import and old messages need no migration`() {
        val json = Json { ignoreUnknownKeys = true }
        val notice = UIMessage.user("你揉了揉AI").copy(
            annotations = listOf(UIMessageAnnotation.AvatarInteraction("user", "assistant")))
        assertEquals(notice, json.decodeFromString<UIMessage>(json.encodeToString(UIMessage.serializer(), notice)))
        assertTrue(UIMessage.user("legacy").annotations.isEmpty())
    }

    @Test
    fun `split bubble targets preserve separate sentences and fenced code blocks`() {
        val message = UIMessage.user("我喜欢牛奶\n但不喜欢咖啡\n```kotlin\nval n = 1\nprintln(n)\n```")
        val segments = reactionSegments(message, Assistant(splitUserBubbleByLine = true))
        assertTrue("我喜欢牛奶" in segments)
        assertTrue("但不喜欢咖啡" in segments)
        assertTrue(segments.any { it.contains("val n = 1\nprintln(n)") })
        assertEquals(listOf(message.parts.filterIsInstance<UIMessagePart.Text>().single().text),
            reactionSegments(message, Assistant(splitUserBubbleByLine = false)))
    }
    @Test
    fun `reaction resolves a quoted sentence without exposing or guessing message IDs`() {
        val assistant = Assistant(splitUserBubbleByLine = true)
        val user = UIMessage.user("喜欢牛奶\n不喜欢咖啡")
        val reply = UIMessage.assistant("知道啦")
        assertEquals(user, resolveReactionTarget(listOf(user, reply), assistant, "by_quote", "牛奶"))
        assertEquals("喜欢牛奶", resolveReactionQuote(user, assistant, "牛奶"))
        assertEquals(reply, resolveReactionTarget(listOf(user, reply), assistant, "last_assistant", ""))
        assertEquals("不喜欢咖啡", resolveReactionQuote(user, assistant, ""))
    }

    @Test
    fun `hidden branch IDs and ambiguous quotes cannot target the wrong message`() {
        val assistant = Assistant()
        val visible = UIMessage.user("牛奶")
        val hidden = UIMessage.user("牛奶")
        try {
            resolveReactionTarget(listOf(visible), assistant, hidden.id.toString(), "")
            fail("Hidden target")
        } catch (_: IllegalStateException) { }
        try {
            resolveReactionTarget(listOf(visible, hidden), assistant, "by_quote", "牛奶")
            fail("Ambiguous quote")
        } catch (_: IllegalArgumentException) { }
    }

}
