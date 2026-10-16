package me.rerere.rikkahub.data.memory

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.isSummaryMemory
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Coordinates storage and transport. Ranking/budgeting and request snapshots are independent pure code. */
class MemoryRecallService(
    private val memoryDAO: MemoryDAO,
    private val index: MemoryIndexDAO,
    client: OkHttpClient,
    context: Context? = null,
    private val history: HistoryMemoryStorage,
) {
    // Paid embedding calls must not inherit transport retries from normal streaming chat.
    private val embedding = OpenAIProvider(
        client.newBuilder().retryOnConnectionFailure(false).callTimeout(8, TimeUnit.SECONDS).build(), context,
    )
    // Manual batch indexing is not on the chat send path; do not impose its 8-second deadline.
    private val indexEmbedding = OpenAIProvider(
        client.newBuilder().retryOnConnectionFailure(false)
            .callTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(), context,
    )
    private val budget = MemoryTextBudget()
    private val queries = MemoryQueryCache()
    private val queryMutex = Mutex()
    private val indexMutex = Mutex()

    private data class EmbeddingConfig(val model: Model, val provider: ProviderSetting.OpenAI, val key: String)

    private fun config(assistant: Assistant, settings: Settings): EmbeddingConfig? {
        val model = assistant.memoryEmbeddingModelId?.let(settings::findModelById) ?: return null
        if (model.type != ModelType.EMBEDDING || model.customBodies.any { it.key == "input" }) return null
        val provider = model.findProvider(settings.providers) as? ProviderSetting.OpenAI ?: return null
        if (!provider.enabled) return null
        // Effective URL/model/custom parameters matter. Never persist a key or raw provider settings.
        val modelIdentity = JsonInstant.encodeToString(model.copy(providerOverwrite = null))
        val key = memoryHash("memory-slices-v1:utf8:800:100:" + modelIdentity +
            provider.id + provider.baseUrl.trimEnd('/') + JsonInstant.encodeToString(model.customBodies) +
            JsonInstant.encodeToString(model.customHeaders))
        return EmbeddingConfig(model, provider, key)
    }

    private fun scope(assistant: Assistant) = if (assistant.useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID
        else assistant.id.toString()

    private suspend fun sources(scope: String): List<MemoryEntity> = memoryDAO.getMemoriesOfAssistant(scope)
        .filterNot { it.isSummaryMemory() }

    private fun slices(sources: List<MemoryEntity>): List<MemorySlice> = sources.flatMap {
        budget.slices(it.assistantId, it.id, it.revision, it.content)
    }

    data class IndexPlan(val pendingChunks: Int, val calls: Int, val hasMore: Boolean = false)

    /** Called from the explicit management action, never from send/generation. */
    suspend fun indexPlan(assistant: Assistant, settings: Settings): IndexPlan = withContext(Dispatchers.IO) {
        val config = config(assistant, settings) ?: error("请先选择 OpenAI 兼容接口的嵌入模型")
        val pending = pendingSlices(assistant, config)
        val count = minOf(pending.size, 2048)
        IndexPlan(count, (count + 31) / 32, pending.size > count)
    }

    private suspend fun pendingSlices(assistant: Assistant, config: EmbeddingConfig): List<MemorySlice> {
        val scope = scope(assistant)
        val existing = index.vectors(scope, config.key).mapTo(hashSetOf()) { it.sliceId }
        val pending = slices(sources(scope)).filter { it.id !in existing }.take(2049).toMutableList()
        if (assistant.memoryHistoryRecallEnabled && pending.size < 2049) {
            val indexed = history.indexedIds(assistant.id.toString(), config.key)
            var after = ""
            while (pending.size < 2049) {
                currentCoroutineContext().ensureActive()
                val page = history.page(assistant.id.toString(), after)
                if (page.isEmpty()) break
                for (source in page) {
                    pending += historyMemorySlices(source).filter { it.id !in indexed }.take(2049 - pending.size)
                    if (pending.size >= 2049) break
                }
                after = page.last().nodeId
            }
        }
        return pending
    }

    suspend fun refreshHistoryKeywords(assistant: Assistant): Int = history.refreshKeywords(assistant.id.toString())

    /** At most 64 sequential calls per explicit action; completed batches survive cancellation. */
    suspend fun buildIndex(
        assistant: Assistant,
        settings: Settings,
        maxChunks: Int = 2048,
    ): Int = withContext(Dispatchers.IO) {
        check(indexMutex.tryLock()) { "已有索引任务正在运行" }
        try {
            val config = config(assistant, settings) ?: error("请先选择 OpenAI 兼容接口的嵌入模型")
            val scope = scope(assistant)
            val sourceRows = sources(scope)
            val available = pendingSlices(assistant, config)
            check(maxChunks == 2048 || available.size <= maxChunks) {
                "待处理记忆已变化，请重新确认索引调用次数"
            }
            val pending = available.take(maxChunks.coerceIn(0, 2048))
            index.removeOtherModels(scope, config.key)
            if (assistant.memoryHistoryRecallEnabled) history.removeOtherModels(assistant.id.toString(), config.key)
            index.removeStaleVectors(scope)
            sourceRows.forEach { index.removeStaleSource(it.id, it.revision, memoryHash(it.content)) }
            var completed = 0
            for (batch in pending.chunked(32)) {
                val vectors = embed(config, batch.map { it.text }, indexEmbedding)
                // Re-read source after the network await: stale batches must never resurrect deleted records.
                val current = sources(scope).associateBy { it.id }
                index.putVectors(batch.zip(vectors).mapNotNull { (slice, vector) ->
                    val source = current[slice.memoryId]
                    if (slice.history != null || source == null || source.revision != slice.revision ||
                        memoryHash(source.content) != slice.sourceHash) null
                    else MemoryVectorEntity(
                        config.key, slice.id, scope, slice.memoryId, slice.revision, slice.sourceHash,
                        JsonInstant.encodeToString(vector),
                    )
                })
                if (assistant.memoryHistoryRecallEnabled) {
                    history.putValid(batch.zip(vectors).mapNotNull { (slice, vector) ->
                        val ref = slice.history ?: return@mapNotNull null
                        HistoryMemoryVector(
                            config.key, slice.id, assistant.id.toString(), ref.conversationId, ref.nodeId, ref.messageId,
                            slice.sourceHash, slice.start, slice.end, JsonInstant.encodeToString(vector),
                        )
                    })
                }
                completed += batch.size
            }
            completed
        } finally {
            indexMutex.unlock()
        }
    }

    private suspend fun embed(
        config: EmbeddingConfig,
        inputs: List<String>,
        provider: OpenAIProvider = embedding,
    ): List<List<Float>> {
        val result = provider.generateEmbedding(config.provider, EmbeddingGenerationParams(
            model = config.model, input = inputs,
            customHeaders = config.model.customHeaders, customBody = config.model.customBodies,
        )).embeddings
        check(result.size == inputs.size && result.isNotEmpty()) { "嵌入接口返回的数量不匹配" }
        val dimension = result.first().size
        check(dimension in 1..8192 && result.all { vector ->
            vector.size == dimension && vector.all { it.isFinite() } && vector.any { it != 0f }
        }) { "嵌入接口返回了无效向量" }
        return result
    }

    /** Prepare once for a whole tool loop; subsequent provider calls reuse the same immutable snapshots. */
    suspend fun prepare(
        assistant: Assistant,
        settings: Settings,
        conversationId: String?,
        messages: List<UIMessage>,
    ): MemoryRecallTurn = withContext(Dispatchers.IO) {
        if (!assistant.enableMemory || !assistant.memoryRecallEnabled || conversationId.isNullOrBlank()) {
            return@withContext MemoryRecallTurn()
        }
        try {
            val scope = scope(assistant)
            val latest = messages.lastOrNull { it.role == MessageRole.USER }
                ?: return@withContext MemoryRecallTurn()
            val hash = memoryMessageHash(latest)
            val query = memoryRecallQuery(messages, latest)
            val historyScope = assistant.id.toString()
            val boundary = if (assistant.memoryHistoryRecallEnabled) {
                history.boundary(historyScope, conversationId, messages)
            } else null
            val retrievalVersion = (config(assistant, settings)?.key ?: "keyword-v1") +
                ":history-v1:${assistant.memoryHistoryRecallEnabled}:$historyScope"
            val key = memoryHash("$conversationId\u0000$scope\u0000${latest.id}\u0000$hash" +
                "\u0000${memoryHash(query)}\u0000$retrievalVersion")
            var snapshot = index.snapshot(key)
            if (snapshot == null) {
                val excerpts = recall(assistant, settings, scope, query, sources(scope), boundary)
                index.putSnapshot(MemoryRecallSnapshotEntity(
                    key, conversationId, scope, latest.id.toString(), hash,
                    JsonInstant.encodeToString(excerpts), System.currentTimeMillis(),
                ))
                index.trimSnapshots()
                // Concurrent retries use the first saved result, never whichever finishes last.
                snapshot = index.snapshot(key)
            }
            val excerpts = try {
                snapshot?.let { JsonInstant.decodeFromString<List<SavedMemoryExcerpt>>(it.excerpts) }.orEmpty()
            } catch (_: SerializationException) { emptyList() }
            MemoryRecallTurn(scope, latest.id.toString(), excerpts, historyScope, boundary)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Index errors must not block chat, fall back to injecting no recall, never the entire memory bank.
            MemoryRecallTurn()
        }
    }

    /** Revalidate sources on every tool step without repeating search or paid embedding calls. */
    suspend fun apply(turn: MemoryRecallTurn, messages: List<UIMessage>): List<UIMessage> {
        val scope = turn.scope ?: return messages
        return try {
            val refs = turn.historyReferences()
            val historical = if (turn.historyScope == null || refs.isEmpty()) emptyMap() else {
                history.sources(turn.historyScope, refs.map { it.nodeId }.distinct()).associateBy { it.reference.key }
            }
            turn.apply(messages, sources(scope).associateBy { it.id }, historical)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            messages
        }
    }

    private suspend fun recall(
        assistant: Assistant, settings: Settings, scope: String, query: String, sources: List<MemoryEntity>,
        boundary: HistoryRecallBoundary?,
    ): List<SavedMemoryExcerpt> {
        if (query.isBlank()) return emptyList()
        val slices = slices(sources)
        val historySlices = if (boundary == null) emptyList() else try {
            history.keywords(assistant.id.toString(), query, boundary)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { emptyList() }
        val keywords = rankKeywordMemories(query, slices + historySlices)
        val vectors = withTimeoutOrNull(8000) {
            try {
                val config = config(assistant, settings) ?: return@withTimeoutOrNull emptyList()
                val byId = slices.associateBy { it.id }
                val indexed = index.vectors(scope, config.key).filter { record ->
                    val slice = byId[record.sliceId]
                    slice != null && slice.revision == record.revision && slice.sourceHash == record.sourceHash
                }
                val historyScope = assistant.id.toString()
                val firstHistoryPage = if (boundary == null) emptyList() else {
                    history.vectors(historyScope, config.key, "", boundary)
                }
                if (indexed.isEmpty() && firstHistoryPage.isEmpty()) return@withTimeoutOrNull emptyList()
                val cacheKey = memoryHash(config.key + "\u0000" + scope + "\u0000" + historyScope + "\u0000" + query)
                val queryVector = queryMutex.withLock {
                    queries.get(cacheKey) ?: embed(config, listOf(query)).single().also { queries.put(cacheKey, it) }
                }
                val memoryHits = indexed.mapNotNull { record ->
                    currentCoroutineContext().ensureActive()
                    val vector = try {
                        JsonInstant.decodeFromString<List<Float>>(record.vector)
                    } catch (_: SerializationException) {
                        return@mapNotNull null
                    }
                    memoryCosine(queryVector, vector)?.let { RankedMemory(byId.getValue(record.sliceId), it) }
                }.sortedWith(compareByDescending<RankedMemory> { it.score }.thenBy { it.slice.id }).take(20)
                val historyHits = if (boundary == null) emptyList() else historyVectorHits(
                    historyScope, config.key, queryVector, firstHistoryPage, boundary,
                )
                (memoryHits + historyHits)
                    .sortedWith(compareByDescending<RankedMemory> { it.score }.thenBy { it.slice.id }).take(20)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }.orEmpty()
        return selectMemoryExcerpts(query, fuseMemoryRanks(keywords, vectors)).map {
            SavedMemoryExcerpt(it.slice.memoryId, it.slice.revision, it.slice.sourceHash, it.text, it.slice.history)
        }
    }

    private data class HistoryScore(val record: HistoryMemoryVector, val score: Double)

    /** Read vector pages and retain only 80 lightweight candidates, not the entire archive's vectors. */
    private suspend fun historyVectorHits(
        scope: String, namespace: String, query: List<Float>, firstPage: List<HistoryMemoryVector>,
        boundary: HistoryRecallBoundary,
    ): List<RankedMemory> {
        val best = java.util.PriorityQueue<HistoryScore>(
            compareBy<HistoryScore> { it.score }.thenByDescending { it.record.sliceId },
        )
        var page = firstPage
        while (page.isNotEmpty()) {
            for (record in page) {
                currentCoroutineContext().ensureActive()
                val vector = try { JsonInstant.decodeFromString<List<Float>>(record.vector) }
                    catch (_: SerializationException) { continue }
                val score = memoryCosine(query, vector) ?: continue
                best += HistoryScore(record.copy(vector = ""), score)
                if (best.size > 80) best.poll()
            }
            page = history.vectors(scope, namespace, page.last().sliceId, boundary)
        }
        val sources = history.sources(scope, best.map { it.record.nodeId }.distinct())
            .filter(boundary::allows).associateBy { it.reference.key }
        val slices = sources.mapValues { historyMemorySlices(it.value).associateBy { slice -> slice.id } }
        return best.mapNotNull { hit ->
            val record = hit.record
            val key = "${record.conversationId}/${record.nodeId}/${record.messageId}"
            val slice = slices[key]?.get(record.sliceId) ?: return@mapNotNull null
            if (slice.sourceHash != record.sourceHash) null else RankedMemory(slice, hit.score)
        }.sortedWith(compareByDescending<RankedMemory> { it.score }.thenBy { it.slice.id }).take(20)
    }

}
