package me.rerere.rikkahub.data.music

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.service.ConversationSession

internal fun ConversationSession.musicCommentaryBusy(): Boolean =
    isGenerating || messageQueue.state.value.messages.isNotEmpty() || submittingMessage != null

/**
 * Music never owns the ordinary generation job. A chat/proactive turn can start immediately,
 * cancels this optional request, and retains responsibility for its existing timer and queue.
 */
internal suspend fun withMusicCommentarySession(
    session: ConversationSession,
    block: suspend () -> Unit,
) {
    if (session.musicCommentaryBusy()) return
    coroutineScope {
        val owner = currentCoroutineContext().job
        val watcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                combine(session.messageQueue.state, session.generationJob) { queue, generation ->
                        queue.messages.isNotEmpty() ||
                            (generation != null && !generation.isCompleted)
                    }
                    .first { it }
                owner.cancel()
            }
        try {
            if (!session.musicCommentaryBusy()) block()
        } finally {
            watcher.cancel()
        }
    }
}
