package me.rerere.rikkahub.service

import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test

class VoiceReplyOwnershipTest {
    @Test
    fun `interrupted voice tools are settled without touching another turn or inactive branch`() {
        val voice =
            UIMessage(role = MessageRole.USER, parts = rawVoiceCallParts("file:///voice.wav"))
        val pending = UIMessagePart.Tool("voice-tool", "test_tool", "{}")
        val completed =
            pending.copy(toolCallId = "done", output = listOf(UIMessagePart.Text("done")))
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending, completed))
        val inactive = reply.copy(id = Uuid.random())
        val other = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("newer")))
        val otherReply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending))
        val conversation =
            Conversation.ofId(
                Uuid.random(),
                messages =
                    listOf(
                        voice.toMessageNode(),
                        reply.toMessageNode().copy(messages = listOf(reply, inactive)),
                        other.toMessageNode(),
                        otherReply.toMessageNode(),
                    ),
            )
        val updated =
            finishVoiceReplyTools(conversation, voice.id) { tool ->
                tool.copy(
                    output = listOf(UIMessagePart.Text("cancelled")),
                    approvalState = ToolApprovalState.Denied("cancelled"),
                )
            }
        val parts = updated.currentMessages[1].parts.filterIsInstance<UIMessagePart.Tool>()
        assertTrue(parts.first().isExecuted)
        assertTrue(parts.first().approvalState is ToolApprovalState.Denied)
        assertEquals(completed, parts.last())
        assertEquals(inactive, updated.messageNodes[1].messages[1])
        assertEquals(otherReply, updated.currentMessages.last())
        assertSame(
            conversation,
            finishVoiceReplyTools(conversation, Uuid.random()) { error("must not run") },
        )
    }

    private fun session(scope: CoroutineScope) =
        ConversationSession(
            id = Uuid.random(),
            initial = Conversation.ofId(Uuid.random()),
            scope = scope,
            debugLog = {},
            onIdle = {},
        )

    @Test
    fun `hang up withdraws only owned queued audio and resolves its observer`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session(scope)
            val reply = CompletableDeferred<String?>()
            val voice =
                session.messageQueue.enqueue(
                    rawVoiceCallParts("file:///voice.wav"),
                    reply = reply,
                )!!
            val other = session.messageQueue.enqueue(listOf(UIMessagePart.Text("normal chat")))!!
            val active = Job()
            session.setJob(active)
            assertEquals(voice, session.cancelVoiceReply(voice.id))
            assertNull(reply.await())
            assertEquals(listOf(other), session.messageQueue.state.value.messages)
            assertTrue(active.isActive)
            assertFalse(session.messageQueue.state.value.paused)
            active.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `barge in cancels the matching submitted voice reply`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session(scope)
            val voice = QueuedMessage(parts = rawVoiceCallParts("file:///voice.wav"))
            session.submittingMessage = voice
            val active = Job()
            session.setJob(active)
            session.cancelVoiceReply(voice.id)
            assertTrue(active.isCancelled)
            assertFalse(session.messageQueue.state.value.paused)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stale voice cleanup cannot cancel a newer or unrelated generation`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session(scope)
            val other = QueuedMessage(parts = listOf(UIMessagePart.Text("newer turn")))
            session.submittingMessage = other
            val active = Job()
            session.setJob(active)
            session.cancelVoiceReply(Uuid.random())
            assertTrue(active.isActive)
            session.submittingMessage = null // Background/proactive work has no owned voice turn.
            session.cancelVoiceReply(other.id)
            assertTrue(active.isActive)
            active.cancel()
        } finally {
            scope.cancel()
        }
    }
}
