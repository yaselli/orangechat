package me.rerere.rikkahub.data.interaction

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ReactionContextTest {
    private val started = LocalDateTime(2026, 10, 25, 12, 0)
    private val millis = started.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
    private val user = UIMessage.user("你好")
    private val oldReply = UIMessage.assistant("你好呀").copy(createdAt = started, finishedAt = started)
    private val latest = UIMessage.user("继续聊聊")
    private val history = listOf(user, oldReply, latest)

    private fun reaction(at: Long = millis + 1000, actor: String = "user") = MessageReaction(
        id = "reaction", conversationId = "chat", messageId = oldReply.id.toString(), actor = actor,
        emoji = CHAT_REACTION_KAOMOJI.first(), quote = "你好呀", createdAt = at,
    )

    private fun text(messages: List<UIMessage>) = messages.flatMap { it.parts }
        .filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    @Test
    fun `successful reply stops old reaction from being announced on every new user turn`() {
        val event = reaction()
        val turn = prepareReactionContext(history, listOf(event), true)
        assertTrue(text(turn.apply(history)).contains(event.emoji))
        assertFalse(text(history).contains("<message_interactions>"))

        val complete = turn.finish(history + UIMessage.assistant("收到啦"))
        assertEquals(setOf(reactionReceipt(event)), complete.last().reactionReceipts())
        assertFalse(text(complete).contains("<message_interactions>"))
        val next = complete + UIMessage.user("换个话题")
        val nextTurn = prepareReactionContext(next, listOf(event), true)
        assertTrue(nextTurn.events.isEmpty())
        assertFalse(text(nextTurn.apply(next)).contains("<message_interactions>"))
        assertEquals("你好呀", oldReply.toText())
    }

    @Test
    fun `failed cancelled and approval-paused generations do not consume pending events`() {
        val event = reaction()
        val turn = prepareReactionContext(history, listOf(event), true)
        turn.apply(history)
        // Failure/cancellation never invoke finish; a paused tool batch cannot acknowledge either.
        val pending = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("call", "lookup", "{}", emptyList()),
        ))
        val paused = history + pending
        assertSame(paused, turn.finish(paused))
        assertEquals(listOf(event), prepareReactionContext(history, listOf(event), true).events)
        assertTrue(history.all { it.reactionReceipts().isEmpty() })
    }

    @Test
    fun `an empty continuation cannot acknowledge events by reusing an old completed reply`() {
        val source = listOf(user, oldReply)
        val turn = prepareReactionContext(source, listOf(reaction()), true)
        turn.apply(source)
        assertSame(source, turn.finish(source))
        assertTrue(oldReply.reactionReceipts().isEmpty())
    }

    @Test
    fun `snapshot stays fixed through tools and a reaction arriving during generation waits for next turn`() {
        val first = reaction()
        val later = reaction(millis + 2000).copy(id = "later", emoji = "👍")
        val turn = prepareReactionContext(history, listOf(first), true)
        val request = turn.apply(history)
        assertEquals(text(request), text(turn.apply(history)))
        assertEquals(1, "<message_interactions>".toRegex().findAll(text(turn.apply(request))).count())

        val reply = UIMessage.assistant("好呀").copy(
            createdAt = LocalDateTime(2026, 10, 25, 12, 1), finishedAt = LocalDateTime(2026, 10, 25, 12, 2),
        )
        val completed = turn.finish(history + reply)
        val next = completed + UIMessage.user("还有呢")
        assertEquals(listOf(later), prepareReactionContext(next, listOf(first, later), true).events)
    }

    @Test
    fun `remove and re-add is a new event while assistant-created reactions are not user notices`() {
        val event = reaction()
        val turn = prepareReactionContext(history, listOf(event), true)
        turn.apply(history)
        val completed = turn.finish(history + UIMessage.assistant("知道啦"))
        val repeated = event.copy(createdAt = event.createdAt + 1)
        val own = reaction(millis + 3000, "assistant")
        assertEquals(listOf(repeated), prepareReactionContext(completed, listOf(repeated, own), true).events)
        assertNotEquals(reactionReceipt(event), reactionReceipt(repeated))
    }

    @Test
    fun `old installation reactions before its completed reply are not replayed during upgrade`() {
        val old = reaction(millis - 1)
        val fresh = reaction(millis + 1)
        assertEquals(listOf(fresh), prepareReactionContext(history, listOf(old, fresh), true).events)
    }

    @Test
    fun `background pass wait and stop can retain local receipts without saving hidden decision text`() {
        for (decision in listOf("[PASS]", "[WAIT:20]", "[STOP]")) {
            val event = reaction()
            val turn = prepareReactionContext(history, listOf(event), true)
            turn.apply(history)
            val decided = turn.finish(history + UIMessage.assistant(decision)).last()
            val storedAnchor = latest.withReactionReceipts(decided.reactionReceipts())
            val storedHistory = history.dropLast(1) + storedAnchor
            assertEquals(latest.parts, storedAnchor.parts)
            assertFalse(text(storedHistory).contains(decision))
            assertTrue(prepareReactionContext(storedHistory, listOf(event), true).events.isEmpty())
        }
    }

    @Test
    fun `receipts preserve other local metadata and survive save restore and copied conversation identity`() {
        val event = reaction()
        val source = UIMessage.assistant("reply").copy(localMetadata = buildJsonObject {
            put("existing_memory_trace", JsonPrimitive("unchanged"))
        })
        val stored = source.withReactionReceipts(setOf(reactionReceipt(event)))
        val json = Json { ignoreUnknownKeys = true }
        val restored = json.decodeFromString<UIMessage>(json.encodeToString(UIMessage.serializer(), stored))
        assertEquals(stored, restored)
        assertEquals(source.localMetadata!!["existing_memory_trace"], restored.localMetadata!!["existing_memory_trace"])
        assertEquals(reactionReceipt(event), reactionReceipt(event.copy(id = "fork", conversationId = "fork")))
        assertNotEquals(reactionReceipt(event), reactionReceipt(event.copy(messageId = "another")))
    }

    @Test
    fun `disabled feature and a truncated-away user anchor never acknowledge unseen events`() {
        val event = reaction()
        val disabled = prepareReactionContext(history, listOf(event), false)
        assertFalse(text(disabled.apply(history)).contains(event.emoji))
        val turn = prepareReactionContext(history, listOf(event), true)
        turn.apply(listOf(UIMessage.system("no user survived transformation")))
        assertTrue(turn.finish(history + UIMessage.assistant("reply")).last().reactionReceipts().isEmpty())
    }
}
