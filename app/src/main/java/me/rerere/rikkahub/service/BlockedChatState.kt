package me.rerere.rikkahub.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

data class BlockedChatState(
    val blocked: Boolean = false,
    val replies: Int = 0,
    val limit: Int = 5,
    val running: Boolean = false,
) {
    val reachedLimit: Boolean get() = blocked && replies >= limit
    val canReply: Boolean get() = blocked && !reachedLimit
    fun afterReply(): BlockedChatState = copy(replies = (replies + 1).coerceAtMost(limit))
}

/** Local conversation state only. Process restart always leaves auto-replies paused. */
internal class BlockedChatStore(context: Context) {
    private val prefs = context.getSharedPreferences("blocked_chats_v1", Context.MODE_PRIVATE)
    private val states = ConcurrentHashMap<Uuid, MutableStateFlow<BlockedChatState>>()
    private val mutex = Mutex()

    fun state(id: Uuid): StateFlow<BlockedChatState> = mutableState(id)
    private fun mutableState(id: Uuid) = states.computeIfAbsent(id) {
        MutableStateFlow(BlockedChatState(
            blocked = prefs.getBoolean("$id/blocked", false),
            replies = prefs.getInt("$id/replies", 0).coerceAtLeast(0),
            limit = prefs.getInt("$id/limit", 5).coerceIn(1, 20),
        ))
    }

    suspend fun update(id: Uuid, transform: (BlockedChatState) -> BlockedChatState) = mutex.withLock {
        val flow = mutableState(id)
        val next = transform(flow.value)
        if (next.copy(running = false) == flow.value.copy(running = false)) {
            flow.value = next
            return@withLock
        }
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            check(prefs.edit().putBoolean("$id/blocked", next.blocked)
                .putInt("$id/replies", next.replies).putInt("$id/limit", next.limit).commit()) {
                "无法保存拉黑状态，请重试"
            }
            flow.value = next
        }
    }
}
