package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedChatReplyLoopTest {
    @Test
    fun `only an actual unblock enables the current state notice`() {
        assertEquals(null, BlockedChatState().afterUnblock().unblockedStatusPrompt())
        val unblocked = BlockedChatState(
            blocked = true, replies = 3, running = true, waiting = true,
            minIntervalMinutes = 7, maxIntervalMinutes = 9,
        ).afterUnblock()
        assertFalse(unblocked.blocked)
        assertFalse(unblocked.running)
        assertFalse(unblocked.waiting)
        assertEquals(7, unblocked.minIntervalMinutes)
        assertEquals(9, unblocked.maxIntervalMinutes)
        assertEquals(0, unblocked.replies)
        assertTrue(unblocked.unblockedStatusPrompt()!!.contains("对方已解除拉黑"))
        assertEquals(unblocked, unblocked.afterUnblock())
    }

    @Test
    fun `blocking again suppresses the previous unblock notice`() {
        assertEquals(null, BlockedChatState(blocked = true, wasUnblocked = true).unblockedStatusPrompt())
        assertEquals(null, BlockedChatState(blocked = true).unblockedStatusPrompt())
    }

    @Test
    fun `reply delays obey defaults fixed intervals and invalid bounds`() {
        val random = Random(42)
        val delays = List(100) { BlockedChatState().nextReplyDelayMillis(random) }
        assertTrue(delays.all { it in 120_000L..300_000L })
        assertTrue(delays.distinct().size > 1)
        assertEquals(420_000L, BlockedChatState(minIntervalMinutes = 7, maxIntervalMinutes = 7)
            .nextReplyDelayMillis(random))
        assertEquals(60_000L, BlockedChatState(minIntervalMinutes = -1, maxIntervalMinutes = 0)
            .nextReplyDelayMillis(random))
    }

    @Test
    fun `cancelling during the wait prevents any next request`() = runBlocking {
        var state = BlockedChatState(blocked = true)
        var calls = 0
        val waiting = CompletableDeferred<Unit>()
        val job = launch {
            runBlockedReplyBatch(
                state = { state },
                generateReply = { calls++; true },
                recordReply = { state = state.afterReply() },
                betweenReplies = {
                    waiting.complete(Unit)
                    awaitCancellation()
                },
            )
        }
        waiting.await()
        assertEquals(1, calls)
        assertEquals(1, state.replies)
        job.cancelAndJoin()
        assertEquals(1, calls)
        assertEquals(1, state.replies)
    }

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
