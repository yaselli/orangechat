package me.rerere.rikkahub.data.music

import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import org.junit.Assert.*
import org.junit.Test

class MusicCommentarySessionTest {
    @Test
    fun `user input cancels optional commentary and remains dispatchable`() = runBlocking {
        val id = Uuid.random()
        val session =
            ConversationSession(
                id,
                Conversation(id, Uuid.random(), messageNodes = emptyList()),
                this,
                debugLog = {},
            ) {}
        val entered = CompletableDeferred<Unit>()
        val music = launch {
            withMusicCommentarySession(session) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            entered.await()
            assertNull(session.getJob())
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("real user input")))
            music.join()
            assertTrue(music.isCancelled)
            assertFalse(session.messageQueue.state.value.paused)
            assertNotNull(session.messageQueue.takeNext())
        } finally {
            music.cancel()
            session.cleanup()
        }
    }

    @Test
    fun `ordinary proactive generation keeps ownership and interrupts commentary`() = runBlocking {
        val id = Uuid.random()
        val session =
            ConversationSession(
                id,
                Conversation(id, Uuid.random(), messageNodes = emptyList()),
                this,
                debugLog = {},
            ) {}
        val entered = CompletableDeferred<Unit>()
        val music = launch {
            withMusicCommentarySession(session) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val proactive = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        try {
            entered.await()
            assertTrue(session.tryClaimGeneration(proactive))
            proactive.start()
            music.join()
            assertTrue(music.isCancelled)
            assertSame(proactive, session.getJob())
            assertTrue(session.isGenerating)
        } finally {
            music.cancel()
            proactive.cancel()
            session.cleanup()
        }
    }

    @Test
    fun `busy conversations never start a commentary model request`() = runBlocking {
        val id = Uuid.random()
        val session =
            ConversationSession(
                id,
                Conversation(id, Uuid.random(), messageNodes = emptyList()),
                this,
                debugLog = {},
            ) {}
        val normal = launch(start = CoroutineStart.LAZY) {}
        var called = false
        try {
            assertTrue(session.tryClaimGeneration(normal))
            withMusicCommentarySession(session) { called = true }
            assertFalse(called)
            assertSame(normal, session.getJob())
        } finally {
            normal.cancel()
            session.cleanup()
        }
    }
}
