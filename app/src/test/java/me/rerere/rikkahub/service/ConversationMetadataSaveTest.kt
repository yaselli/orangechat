package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationMetadataSaveTest {
    @Test
    fun `title completion preserves a newer reply alternatives and metadata`() = runBlocking {
        withSession { session ->
            val latest = session.state.value.copy(
                messageNodes = session.state.value.messageNodes + MessageNode.of(UIMessage.assistant("new reply")),
                folderId = Uuid.random(),
                chatSuggestions = listOf("new suggestion"),
            )
            session.state.value = latest
            session.saveGeneratedTitle("generated") {
                assertTrue(session.isInUse)
                true
            }
            assertFalse(session.isInUse)
            assertEquals(latest.copy(title = "generated"), session.state.value)
            assertSame(latest.messageNodes, session.state.value.messageNodes)
        }
    }

    @Test
    fun `suggestion completion preserves newer title and deleted message state`() = runBlocking {
        withSession { session ->
            val latest = session.state.value.copy(title = "renamed", messageNodes = emptyList())
            session.state.value = latest
            session.saveGeneratedSuggestions(listOf("one", "two")) { true }
            assertEquals(latest.copy(chatSuggestions = listOf("one", "two")), session.state.value)
            assertTrue(session.state.value.messageNodes.isEmpty())
        }
    }

    @Test
    fun `missing database row leaves both metadata fields untouched`() = runBlocking {
        withSession { session ->
            val before = session.state.value
            session.saveGeneratedTitle("late title") { false }
            session.saveGeneratedSuggestions(listOf("late suggestion")) { false }
            assertSame(before, session.state.value)
        }
    }

    @Test
    fun `failed metadata persistence does not publish an unsaved result`() = runBlocking {
        withSession { session ->
            val before = session.state.value
            val failure = IllegalStateException("save failed")
            try {
                session.saveGeneratedTitle("unsaved") { throw failure }
                fail("Persistence failure must propagate")
            } catch (actual: IllegalStateException) {
                assertSame(failure, actual)
            }
            assertSame(before, session.state.value)
            // The mutex is released even after a failure.
            session.saveGeneratedSuggestions(listOf("saved")) { true }
            assertEquals(before.copy(chatSuggestions = listOf("saved")), session.state.value)
        }
    }

    @Test
    fun `stream save and suggestion save wait for title persistence and retain all changes`() = runBlocking {
        withSession { session ->
            coroutineScope {
                val savingTitle = CompletableDeferred<Unit>()
                val finishTitle = CompletableDeferred<Unit>()
                val title = launch(start = CoroutineStart.UNDISPATCHED) {
                    session.saveGeneratedTitle("generated") {
                        savingTitle.complete(Unit)
                        finishTitle.await()
                        true
                    }
                }
                savingTitle.await()
                val reply = MessageNode.of(UIMessage.assistant("stream finished"))
                val stream = launch(start = CoroutineStart.UNDISPATCHED) {
                    session.saveMutex.withLock {
                        session.state.value = session.state.value.copy(
                            messageNodes = session.state.value.messageNodes + reply
                        )
                    }
                }
                val suggestions = launch(start = CoroutineStart.UNDISPATCHED) {
                    session.saveGeneratedSuggestions(listOf("next")) { true }
                }
                try {
                    assertFalse(stream.isCompleted)
                    assertFalse(suggestions.isCompleted)
                } finally {
                    finishTitle.complete(Unit)
                }
                title.join()
                stream.join()
                suggestions.join()
                assertEquals("generated", session.state.value.title)
                assertEquals(listOf("next"), session.state.value.chatSuggestions)
                assertEquals(reply, session.state.value.messageNodes.last())
            }
        }
    }

    private suspend fun withSession(block: suspend (ConversationSession) -> Unit) = coroutineScope {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val initial = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(
                messages = listOf(UIMessage.assistant("old"), UIMessage.assistant("selected")),
                selectIndex = 1,
                isFavorite = true,
            )),
            isPinned = true,
            customSystemPrompt = "custom prompt",
            workspaceCwd = "/workspace",
        )
        // JVM tests exercise real reference counting and locking without Android's Log stub.
        val session = ConversationSession(initial.id, initial, scope, debugLog = {}) {}
        try {
            block(session)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }
}
