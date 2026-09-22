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
import kotlin.random.Random

data class BlockedChatState(
    val blocked: Boolean = false,
    val replies: Int = 0,
    val limit: Int = 5,
    val running: Boolean = false,
    val wasUnblocked: Boolean = false,
    val minIntervalMinutes: Int = 2,
    val maxIntervalMinutes: Int = 5,
    val waiting: Boolean = false,
) {
    val reachedLimit: Boolean get() = blocked && replies >= limit
    val canReply: Boolean get() = blocked && !reachedLimit
    fun afterReply(): BlockedChatState = copy(replies = (replies + 1).coerceAtMost(limit))
    fun afterUnblock(): BlockedChatState = if (blocked) {
        BlockedChatState(
            wasUnblocked = true, limit = limit,
            minIntervalMinutes = minIntervalMinutes, maxIntervalMinutes = maxIntervalMinutes,
        )
    } else this

    fun nextReplyDelayMillis(random: Random = Random.Default): Long {
        val minimum = minIntervalMinutes.coerceIn(1, 30) * 60_000L
        val maximum = maxIntervalMinutes.coerceIn(minIntervalMinutes.coerceIn(1, 30), 30) * 60_000L
        return random.nextLong(minimum, maximum + 1)
    }

    fun unblockedStatusPrompt(): String? = if (!blocked && wasUnblocked) {
        "<application_chat_status>应用状态通知，不是用户发言：对方已解除拉黑，当前聊天可以正常收发消息。" +
            "历史中的被拉黑状态已失效，请按当前状态理解后续对话。" +
            "此状态不代表用户说了新的话，也不表示已经过去多久；不必每轮重复确认解除拉黑。" +
            "</application_chat_status>"
    } else null
}

/** Local conversation state only. Process restart always leaves auto-replies paused. */
internal class BlockedChatStore(context: Context) {
    private val prefs = context.getSharedPreferences("blocked_chats_v1", Context.MODE_PRIVATE)
    private val states = ConcurrentHashMap<Uuid, MutableStateFlow<BlockedChatState>>()
    private val mutex = Mutex()

    fun state(id: Uuid): StateFlow<BlockedChatState> = mutableState(id)
    private fun mutableState(id: Uuid) = states.computeIfAbsent(id) {
        val minimum = prefs.getInt("$id/minIntervalMinutes", 2).coerceIn(1, 30)
        MutableStateFlow(BlockedChatState(
            blocked = prefs.getBoolean("$id/blocked", false),
            replies = prefs.getInt("$id/replies", 0).coerceAtLeast(0),
            limit = prefs.getInt("$id/limit", 5).coerceIn(1, 20),
            wasUnblocked = prefs.getBoolean("$id/wasUnblocked", false),
            minIntervalMinutes = minimum,
            maxIntervalMinutes = prefs.getInt("$id/maxIntervalMinutes", 5).coerceIn(minimum, 30),
        ))
    }

    suspend fun update(id: Uuid, transform: (BlockedChatState) -> BlockedChatState) = mutex.withLock {
        val flow = mutableState(id)
        val next = transform(flow.value)
        if (next.copy(running = false, waiting = false) == flow.value.copy(running = false, waiting = false)) {
            flow.value = next
            return@withLock
        }
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            check(prefs.edit().putBoolean("$id/blocked", next.blocked)
                .putInt("$id/replies", next.replies).putInt("$id/limit", next.limit)
                .putBoolean("$id/wasUnblocked", next.wasUnblocked)
                .putInt("$id/minIntervalMinutes", next.minIntervalMinutes)
                .putInt("$id/maxIntervalMinutes", next.maxIntervalMinutes).commit()) {
                "无法保存拉黑状态，请重试"
            }
            flow.value = next
        }
    }
}
