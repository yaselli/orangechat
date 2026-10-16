package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.planCompaction
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationCompactionSaveTest {
    @Test
    fun `metadata arriving while database save is suspended survives completion`() = runBlocking {
        withSession { session ->
            val result = session.state.value.planCompaction(0).result("summary")
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            coroutineScope {
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    assertTrue(session.saveCompaction(null, result) { entered.complete(Unit); finish.await(); true })
                }
                entered.await()
                session.state.value = session.state.value.copy(title = "changed during save")
                finish.complete(Unit)
                job.join()
            }
            assertEquals("changed during save", session.state.value.title)
            assertEquals(result, session.state.value.contextCompaction)
        }
    }

    @Test
    fun `history edit during suspended persistence is not overwritten by late result`() = runBlocking {
        withSession { session ->
            val result = session.state.value.planCompaction(0).result("obsolete")
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            coroutineScope {
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    assertFalse(session.saveCompaction(null, result) { entered.complete(Unit); finish.await(); true })
                }
                entered.await()
                session.state.value = session.state.value.copy(messageNodes = emptyList())
                finish.complete(Unit)
                job.join()
            }
            assertTrue(session.state.value.messageNodes.isEmpty())
            assertNull(session.state.value.contextCompaction)
        }
    }

    @Test
    fun `late summary completion preserves new reply title suggestions and branches`() = runBlocking {
        withSession { session ->
            val old = session.state.value
            val result = old.planCompaction(0).result("summary")
            val latest = old.copy(
                title = "renamed", chatSuggestions = listOf("suggestion"),
                messageNodes = old.messageNodes + MessageNode.of(UIMessage.user("new message")),
            )
            session.state.value = latest
            assertTrue(session.saveCompaction(null, result) {
                assertTrue(session.isInUse)
                true
            })
            assertEquals(latest.copy(contextCompaction = result), session.state.value)
            assertSame(latest.messageNodes, session.state.value.messageNodes)
            assertFalse(session.isInUse)
        }
    }

    @Test
    fun `edited history rejects obsolete result before persistence`() = runBlocking {
        withSession { session ->
            val result = session.state.value.planCompaction(0).result("obsolete")
            val changed = session.state.value.copy(messageNodes = emptyList())
            session.state.value = changed
            var persisted = false
            assertFalse(session.saveCompaction(null, result) { persisted = true; true })
            assertFalse(persisted)
            assertSame(changed, session.state.value)
        }
    }

    @Test
    fun `deleted database row cannot publish or resurrect a summary`() = runBlocking {
        withSession { session ->
            val old = session.state.value
            assertFalse(session.saveCompaction(null, old.planCompaction(0).result("late")) { false })
            assertSame(old, session.state.value)
        }
    }

    @Test
    fun `another completed compact wins and stale compact cannot replace it`() = runBlocking {
        withSession { session ->
            val old = session.state.value
            val winner = old.planCompaction(0).result("winner")
            val stale = old.planCompaction(0).result("stale")
            assertTrue(session.saveCompaction(null, winner) { true })
            assertFalse(session.saveCompaction(null, stale) { fail("Must not persist"); true })
            assertEquals(winner, session.state.value.contextCompaction)
        }
    }

    @Test
    fun `failed or cancelled persistence publishes nothing and releases lock and reference`() = runBlocking {
        withSession { session ->
            val old = session.state.value
            val summary = old.planCompaction(0).result("summary")
            for (failure in listOf(IllegalStateException("failed"), CancellationException("cancelled"))) {
                try {
                    session.saveCompaction(null, summary) { throw failure }
                    fail("Must throw")
                } catch (actual: Exception) { assertSame(failure, actual) }
                assertSame(old, session.state.value)
                assertFalse(session.saveMutex.isLocked)
                assertFalse(session.isInUse)
            }
        }
    }

    @Test
    fun `manual summary edit preserves coverage history and newly appended messages`() = runBlocking {
        withSession { session ->
            val old = session.state.value
            val first = old.planCompaction(0).result("generated summary")
            assertTrue(session.saveCompaction(null, first) { true })
            val appended = session.state.value.copy(
                messageNodes = old.messageNodes + MessageNode.of(UIMessage.user("new message")),
            )
            session.state.value = appended
            val edited = first.copy(summary = "my corrected facts")
            assertTrue(session.saveCompaction(first, edited) { true })
            assertEquals(first.coveredMessageCount, edited.coveredMessageCount)
            assertEquals(first.coveredPrefixHash, edited.coveredPrefixHash)
            assertSame(appended.messageNodes, session.state.value.messageNodes)
            assertEquals("my corrected facts", session.state.value.contextCompaction?.summary)
            assertTrue(edited.matches(session.state.value.currentMessages))
        }
    }

    @Test
    fun `stale manual editor cannot overwrite newer summary or edited source history`() = runBlocking {
        withSession { session ->
            val old = session.state.value
            val first = old.planCompaction(0).result("generated summary")
            assertTrue(session.saveCompaction(null, first) { true })
            val winner = first.copy(summary = "newer saved summary")
            assertTrue(session.saveCompaction(first, winner) { true })
            assertFalse(session.saveCompaction(first, first.copy(summary = "stale draft")) {
                fail("Stale editor must not persist")
                true
            })
            assertEquals(winner, session.state.value.contextCompaction)
            session.state.value = session.state.value.copy(messageNodes = emptyList())
            assertFalse(session.saveCompaction(winner, winner.copy(summary = "invalid source")) {
                fail("Invalid source must not persist")
                true
            })
            assertTrue(session.state.value.messageNodes.isEmpty())
        }
    }

    private suspend fun withSession(block: suspend (ConversationSession) -> Unit) = coroutineScope {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(UIMessage.user("question")), MessageNode(
                messages = listOf(UIMessage.assistant("alternative"), UIMessage.assistant("answer")),
                selectIndex = 1, isFavorite = true,
            )),
        )
        val session = ConversationSession(conversation.id, conversation, scope, debugLog = {}) {}
        try { block(session) } finally { session.cleanup(); scope.cancel() }
    }
}
