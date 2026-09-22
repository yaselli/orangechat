package me.rerere.rikkahub.service

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Runs one user-approved batch. Errors and cancellation propagate; there is no retry loop. */
internal suspend fun runBlockedReplyBatch(
    state: () -> BlockedChatState,
    generateReply: suspend () -> Boolean,
    recordReply: suspend () -> Unit,
    betweenReplies: suspend () -> Unit,
) {
    while (state().canReply) {
        currentCoroutineContext().ensureActive()
        if (!generateReply()) return
        currentCoroutineContext().ensureActive()
        recordReply()
        if (state().canReply) betweenReplies()
    }
}
