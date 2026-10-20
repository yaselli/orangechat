package me.rerere.rikkahub.data.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

class MemoryAutoIndexService(
    private val dao: MemoryAutoIndexDAO, private val settings: () -> Settings, private val recall: MemoryRecallService,
) {
    private val mutex = Mutex()
    fun latest(owner: Uuid) = dao.latest(owner.toString())
    suspend fun dailyUsed(owner: Uuid): Int = dao.batchesSince(owner.toString(),
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())

    /** One bounded request after a completed reply; never wakes the phone, loops through backlog or retries. */
    suspend fun afterReply(owner: Uuid, nodeIds: List<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = settings()
            val assistant = snapshot.getAssistantById(owner) ?: return@withLock
            if (!assistant.enableMemory || !assistant.memoryRecallEnabled || !assistant.memoryAutoIndexEnabled ||
                assistant.memoryAutoIndexSinceMillis <= 0) return@withLock
            val namespace = recall.embeddingNamespace(assistant, snapshot) ?: return@withLock
            val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (dao.batchesSince(owner.toString(), today) >= assistant.memoryAutoIndexDailyLimit.coerceIn(1, 100)) return@withLock
            val attempted = dao.attempted(owner.toString(), namespace).toHashSet()
            val slices = recall.incrementalSlices(assistant, snapshot, nodeIds.takeLast(64), attempted)
            if (slices.isEmpty()) return@withLock
            val now = System.currentTimeMillis()
            val batch = memoryHash(owner.toString() + namespace + slices.joinToString { it.id })
            if (settings().getAssistantById(owner) != assistant) return@withLock
            val claimed = dao.claim(slices.map { MemoryIndexAttempt(owner.toString(), namespace, it.id, batch,
                    "running", "正在更新；任务中断或失败不自动重试。", now) })
            val inputs = slices.zip(claimed).filter { it.second != -1L }.map { it.first }
            if (inputs.isEmpty()) return@withLock
            try {
                check(settings() == snapshot) { "Index settings changed" }
                val count = recall.indexIncremental(assistant, snapshot, inputs) {
                    settings() == snapshot
                }
                dao.finish(owner.toString(), batch, "completed", "本批处理 $count 个片段，写入前校验来源；一次嵌入请求。")
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { dao.finish(owner.toString(), batch, "interrupted", "自动索引中断，本批不自动重试。") }
                throw cancelled
            } catch (_: Exception) {
                dao.finish(owner.toString(), batch, "failed", "自动索引失败，本批不自动重试；可手动更新索引。")
            }
        }
    }
}
