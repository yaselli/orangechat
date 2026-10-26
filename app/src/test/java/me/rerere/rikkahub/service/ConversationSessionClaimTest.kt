package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionClaimTest {
    @Test
    fun `a claimed lazy task already owns generation`() = runBlocking {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation(id, Uuid.random(), messageNodes = emptyList()), this) {}
        val first = launch(start = CoroutineStart.LAZY) {}
        val second = launch(start = CoroutineStart.LAZY) {}
        try {
            assertTrue(session.tryClaimGeneration(first))
            assertTrue(session.isGenerating)
            assertFalse(session.tryClaimGeneration(second))
        } finally {
            second.cancel()
            session.cleanup()
        }
    }

    @Test
    fun `old completion cannot clear replacement task`() = runBlocking {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation(id, Uuid.random(), messageNodes = emptyList()), this) {}
        val finishOldTask = CompletableDeferred<Unit>()
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { finishOldTask.await() }
            }
        }
        val replacement = launch(start = CoroutineStart.LAZY) {}
        try {
            session.setJob(old)
            session.setJob(replacement)
            finishOldTask.complete(Unit)
            old.join()
            assertSame(replacement, session.getJob())
        } finally {
            finishOldTask.complete(Unit)
            session.cleanup()
        }
    }
    @Test
    fun `queued input and submitting input both block proactive claims`() = runBlocking {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation(id, Uuid.random(), messageNodes = emptyList()), this,
            debugLog = {}) {}
        val candidate = launch(start = CoroutineStart.LAZY) {}
        try {
            session.messageQueue.enqueue(listOf(me.rerere.ai.ui.UIMessagePart.Text("waiting")))
            assertTrue(session.isInUse)
            assertFalse(session.tryClaimGeneration(candidate))
            session.submittingMessage = session.messageQueue.takeNext()
            assertFalse(session.tryClaimGeneration(candidate))
            session.submittingMessage = null
            assertTrue(session.tryClaimGeneration(candidate))
        } finally {
            candidate.cancel()
            session.cleanup()
        }
    }

    @Test
    fun `immediate cancellation of replaced task does not report replacement finished`() = runBlocking {
        val id = Uuid.random()
        val finished = mutableListOf<Throwable?>()
        val session = ConversationSession(id, Conversation(id, Uuid.random(), messageNodes = emptyList()), this,
            debugLog = {}, onGenerationFinished = { _, cause -> finished.add(cause) }) {}
        val old = launch(start = CoroutineStart.LAZY) {}
        val replacement = launch(start = CoroutineStart.LAZY) {}
        session.setJob(old)
        session.setJob(replacement)
        assertTrue(finished.isEmpty())
        assertSame(replacement, session.getJob())
        replacement.start()
        replacement.join()
        assertTrue(finished.size == 1 && finished.single() == null)
        session.cleanup()
    }

    @Test
    fun `cancelling optional background generation leaves user input dispatchable`() = runBlocking {
        val id = Uuid.random()
        lateinit var session: ConversationSession
        session = ConversationSession(id, Conversation(id, Uuid.random(), messageNodes = emptyList()), this,
            debugLog = {}, onGenerationFinished = { _, cause -> if (cause != null) session.messageQueue.pause() }) {}
        val background = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        try {
            assertTrue(session.tryClaimGeneration(background, pauseQueueOnFailure = false))
            background.start()
            session.messageQueue.enqueue(listOf(me.rerere.ai.ui.UIMessagePart.Text("real user input")))
            background.cancel()
            background.join()
            assertFalse(session.messageQueue.state.value.paused)
            assertTrue(session.messageQueue.takeNext() != null)
        } finally { session.cleanup() }
    }

    @Test
    fun `ordinary generation cancellation still pauses its pending queue`() = runBlocking {
        val id = Uuid.random()
        lateinit var session: ConversationSession
        session = ConversationSession(id, Conversation(id, Uuid.random(), messageNodes = emptyList()), this,
            debugLog = {}, onGenerationFinished = { _, cause -> if (cause != null) session.messageQueue.pause() }) {}
        val normal = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        try {
            assertTrue(session.tryClaimGeneration(normal))
            normal.start()
            session.messageQueue.enqueue(listOf(me.rerere.ai.ui.UIMessagePart.Text("queued")))
            normal.cancel()
            normal.join()
            assertTrue(session.messageQueue.state.value.paused)
            assertTrue(session.messageQueue.takeNext() == null)
        } finally { session.cleanup() }
    }

}
