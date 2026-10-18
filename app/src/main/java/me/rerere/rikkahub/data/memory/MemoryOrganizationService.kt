package me.rerere.rikkahub.data.memory

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.isSummaryMemory
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/** App-lifetime background work after a successful reply, never part of the chat/tool request pipeline. */
class MemoryOrganizationService(
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val history: HistoryMemoryStorage,
    client: OkHttpClient,
    context: Context,
) {
    private val providers = ProviderManager(
        client.newBuilder().retryOnConnectionFailure(false)
            .callTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(), context,
    )
    private val mutex = Mutex()
    private val dao get() = database.memoryOrganizationDao()

    fun latest(owner: Uuid) = dao.latest(owner.toString())

    @Serializable
    private data class SourceInput(val sourceMessageId: String, val role: String, val time: String, val text: String)

    private fun scope(assistant: Assistant) = if (assistant.useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID
        else assistant.id.toString()

    private fun current(assistant: Assistant): Boolean {
        val latest = settingsStore.settingsFlow.value.getAssistantById(assistant.id) ?: return false
        return latest.enableMemory && latest.memoryAutoOrganizeEnabled &&
            latest.memoryOrganizationModelId == assistant.memoryOrganizationModelId &&
            latest.memoryGraphEnabled == assistant.memoryGraphEnabled &&
            latest.useGlobalMemory == assistant.useGlobalMemory &&
            latest.memoryOrganizationSinceMillis == assistant.memoryOrganizationSinceMillis
    }

    /** Sequential jobs re-read claims, so queued completions cannot multiply paid calls for one batch. */
    suspend fun afterReply(
        owner: Uuid,
        conversationId: String,
        messages: List<OrganizationMessageReference>,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val settings = settingsStore.settingsFlow.value
            val assistant = settings.getAssistantById(owner) ?: return@withLock
            if (!assistant.enableMemory || !assistant.memoryAutoOrganizeEnabled ||
                assistant.memoryOrganizationSinceMillis <= 0) return@withLock
            val model = assistant.memoryOrganizationModelId?.let(settings::findModelById) ?: return@withLock
            if (model.type != ModelType.CHAT) return@withLock
            val provider = model.findProvider(settings.providers)?.takeIf { it.enabled } ?: return@withLock
            val memoryScope = scope(assistant)
            val attempted = dao.batches(owner.toString(), conversationId).flatMap {
                // A damaged journal must fail closed; never repeat a paid batch on decode failure.
                JsonInstant.decodeFromString<List<String>>(it.messageIds)
            }.toHashSet()
            val pending = messages.filter {
                it.messageId !in attempted &&
                    (organizationSourceMillis(it.createdAt) ?: Long.MIN_VALUE) >=
                    assistant.memoryOrganizationSinceMillis
            }.associateBy { it.nodeId }
            if (pending.isEmpty()) return@withLock
            val sources = pending.keys.toList().chunked(64).flatMap { history.sources(owner.toString(), it) }
                .filter { it.conversationId == conversationId && pending[it.nodeId]?.messageId == it.messageId }
            val plan = planMemoryOrganization(
                sources, attempted, assistant.memoryOrganizationSinceMillis,
                assistant.memoryOrganizationInterval.coerceIn(5, 30),
            ) ?: return@withLock
            val batch = MemoryOrganizationBatch(
                id = memoryHash("${owner}\u0000$memoryScope\u0000" +
                    "${assistant.memoryOrganizationSinceMillis}\u0000${plan.key}"),
                owner = owner.toString(), scope = memoryScope, conversationId = conversationId,
                messageIds = JsonInstant.encodeToString(plan.attempted.map { it.messageId }),
                state = "running", detail = "正在整理；若任务中断，本批不自动重试。", createdAt = System.currentTimeMillis(),
            )
            if (!current(assistant) || dao.claim(batch) == -1L) return@withLock
            try {
                if (plan.sources.none { it.role == "user" }) {
                    dao.finish(batch.id, "skipped", "本批原文过长，已跳过；没有调用模型，原对话保留。")
                    return@withLock
                }
                val existing = database.memoryDao().getMemoriesOfAssistant(memoryScope)
                    .filterNot { it.isSummaryMemory() }
                val query = plan.sources.filter { it.role == "user" }.joinToString("\n") { it.text }
                val related = rankKeywordMemories(query, existing.flatMap {
                    MemoryTextBudget().slices(it.assistantId, it.id, it.revision, it.content)
                }).distinctBy { it.slice.memoryId }.take(20).map { it.slice.text }
                val existingText = MemoryTextBudget().prefix(related.joinToString("\n"), 6000)
                val inputs = plan.sources.map { SourceInput(it.messageId, it.role, it.createdAt, it.text) }
                // Exactly one generation call. No tools, chat persona, attachments, trace metadata or fallback model.
                val response = withTimeout(65000) {
                    providers.getProviderByType(provider).generateText(
                        providerSetting = provider,
                        messages = listOf(
                            UIMessage.system(MEMORY_ORGANIZATION_PROMPT + if (assistant.memoryGraphEnabled) {
                                "\n开启图谱时最多提取4条记忆；每条额外带 graph 字段。\n" + MEMORY_GRAPH_INSTRUCTIONS
                            } else ""),
                            UIMessage.user("已有相关记忆（仅供去重）：\n$existingText\n" +
                                "本批聊天原文：\n${JsonInstant.encodeToString(inputs)}"),
                        ),
                        params = TextGenerationParams(
                            model = model.copy(tools = emptySet()),
                            maxTokens = if (assistant.memoryGraphEnabled) 4096 else 2048,
                            customHeaders = model.customHeaders,
                        ),
                    ).choices.firstOrNull()?.message?.toText() ?: error("模型返回空结果")
                }
                val facts = parseOrganizedFacts(response, plan.sources)
                database.withTransaction {
                    check(current(assistant)) { "自动记忆设置已改变，本批不写入" }
                    val originals = plan.sources.map { it.nodeId }.chunked(64)
                        .flatMap { history.sources(owner.toString(), it) }.associateBy { it.reference.key }
                    check(plan.sources.all { originals[it.reference.key]?.hash == it.hash }) {
                        "原对话已修改、删除或切换分支，本批不写入"
                    }
                    val latestMemories = database.memoryDao().getMemoriesOfAssistant(memoryScope)
                    val byMemoryId = latestMemories.associateBy { it.id }
                    check(existing.all { byMemoryId[it.id]?.revision == it.revision }) {
                        "已有记忆在整理期间被修改或删除，本批不写入"
                    }
                    val contents = latestMemories.mapTo(hashSetOf()) { normalizedMemory(it.content) }
                    val byId = plan.sources.associateBy { it.messageId }
                    var added = 0
                    for (fact in facts) {
                        if (!contents.add(normalizedMemory(fact.content))) continue
                        val source = byId.getValue(fact.sourceMessageId)
                        val now = System.currentTimeMillis()
                        val id = database.memoryDao().insertMemory(MemoryEntity(
                            assistantId = memoryScope, content = fact.content.trim(), sourceKind = "automatic",
                            sourceConversationId = conversationId, sourceMessageId = source.messageId,
                            createdAt = now, updatedAt = now,
                        )).toInt()
                        dao.evidence(MemoryEvidence(
                            id, owner.toString(), conversationId, source.nodeId, source.messageId,
                            source.hash, source.role, source.createdAt, fact.quote,
                        ))
                        if (assistant.memoryGraphEnabled) {
                            database.memoryGraphDao().put(MemoryGraphRecord(
                                id, owner.toString(), memoryScope, 1, memoryHash(fact.content.trim()),
                                JsonInstant.encodeToString(fact.graph), fact.quote, now,
                            ))
                        }
                        added++
                    }
                    dao.finish(batch.id, "completed", "已整理，新增 $added 条记忆。" +
                        if (plan.skipped.isEmpty()) "" else "${plan.skipped.size} 条过长原文已跳过。")
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { dao.finish(batch.id, "interrupted", "整理已中断，本批不自动重试。") }
                throw cancelled
            } catch (_: Exception) {
                // Do not expose upstream bodies, source text or credentials in logs/status.
                dao.finish(batch.id, "failed", "本批整理失败，未新增记忆；不会自动重试，聊天和原文保留。")
            }
        }
    }

    suspend fun evidence(id: Int, memoryScope: String): Pair<MemoryEvidence, Boolean>? = withContext(Dispatchers.IO) {
        val evidence = dao.evidence(id, memoryScope) ?: return@withContext null
        val original = history.sources(evidence.owner, listOf(evidence.nodeId)).firstOrNull {
            it.conversationId == evidence.conversationId && it.messageId == evidence.messageId
        }
        evidence to (original?.hash == evidence.sourceHash)
    }
}
