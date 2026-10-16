/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemorySource

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.filterNot { it.isSummaryMemory() }
                    .map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .filterNot { it.isSummaryMemory() }
            .map { AssistantMemory(it.id, it.content) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.filterNot { it.isSummaryMemory() }
                    .map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .filterNot { it.isSummaryMemory() }
            .map { AssistantMemory(it.id, it.content) }
    }

    private fun MemoryEntity.isSummaryMemory(): Boolean {
        return content.startsWith("[daily_summary]") ||
            content.startsWith("[phase_summary]") ||
            content.startsWith("[auto_summary]")
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(
        id: Int,
        content: String,
        source: MemorySource = MemorySource(),
        assistantId: String? = null,
    ): AssistantMemory {
        require(content.isNotBlank()) { "记忆内容不能为空" }
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        require(assistantId == null || old.assistantId == assistantId) { "不能修改其他助手的记忆" }
        check(memoryDAO.updateContentIfCurrent(
            id, old.assistantId, old.revision, content, System.currentTimeMillis(),
            source.kind, source.conversationId, source.messageId,
        ) == 1) { "记忆已变化或删除，请重新读取后再修改" }
        return AssistantMemory(id, content)
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        source: MemorySource = MemorySource(),
    ): AssistantMemory {
        require(content.isNotBlank()) { "记忆内容不能为空" }
        val now = System.currentTimeMillis()
        val memory = AssistantMemory(
            id = 0,
            content = content,
        )
        val newMemory = memory.copy(
            id = memoryDAO.insertMemory(
                MemoryEntity(
                    assistantId = assistantId,
                    content = memory.content,
                    sourceKind = source.kind,
                    sourceConversationId = source.conversationId,
                    sourceMessageId = source.messageId,
                    createdAt = now,
                    updatedAt = now,
                )
            ).toInt()
        )
        return newMemory
    }

    suspend fun deleteMemory(id: Int, assistantId: String? = null) {
        if (assistantId == null) {
            memoryDAO.deleteMemory(id)
        } else {
            check(memoryDAO.deleteMemoryInScope(id, assistantId) == 1) { "记忆不存在或不属于当前助手" }
        }
    }
}
