package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedChatReplyLoopTest {
    @Test
    fun `one consent permits exactly five complete replies`() = runBlocking {
        var state = BlockedChatState(blocked = true)
        var calls = 0
        var gaps = 0
        runBlockedReplyBatch(
            state = { state },
            generateReply = { calls++; true },
            recordReply = { state = state.afterReply() },
            betweenReplies = { gaps++ },
        )
        assertEquals(5, calls)
        assertEquals(4, gaps)
        assertEquals(5, state.replies)
        assertTrue(state.reachedLimit)
        assertTrue(state.blocked) // Reaching the limit never unblocks the chat.
    }

    @Test
    fun `error or incomplete reply pauses without consuming the next reply`() = runBlocking {
        var state = BlockedChatState(blocked = true)
        var calls = 0
        runBlockedReplyBatch(
            state = { state },
            generateReply = { ++calls < 2 },
            recordReply = { state = state.afterReply() },
            betweenReplies = {},
        )
        assertEquals(2, calls)
        assertEquals(1, state.replies)
    }

    @Test
    fun `remaining allowance survives a pause and is not reset implicitly`() = runBlocking {
        var state = BlockedChatState(blocked = true, replies = 4, limit = 5)
        var calls = 0
        repeat(2) {
            runBlockedReplyBatch(
                state = { state },
                generateReply = { calls++; true },
                recordReply = { state = state.afterReply() },
                betweenReplies = {},
            )
        }
        assertEquals(1, calls)
        assertEquals(5, state.replies)
    }

    @Test
    fun `cancellation and transport failure escape without retries`() = runBlocking {
        for (failure in listOf(CancellationException("stopped"), IllegalStateException("offline"))) {
            var calls = 0
            var recorded = 0
            val result = runCatching {
                runBlockedReplyBatch(
                    state = { BlockedChatState(blocked = true) },
                    generateReply = { calls++; throw failure },
                    recordReply = { recorded++ },
                    betweenReplies = {},
                )
            }
            assertEquals(failure, result.exceptionOrNull())
            assertEquals(1, calls)
            assertEquals(0, recorded)
        }
    }

    @Test
    fun `unblocking between turns stops further requests`() = runBlocking {
        var state = BlockedChatState(blocked = true)
        var calls = 0
        runBlockedReplyBatch(
            state = { state },
            generateReply = { calls++; true },
            recordReply = { state = state.afterReply() },
            betweenReplies = { state = BlockedChatState() },
        )
        assertEquals(1, calls)
        assertFalse(state.blocked)
        assertFalse(BlockedChatState(blocked = true, replies = 2).running)
    }
}
