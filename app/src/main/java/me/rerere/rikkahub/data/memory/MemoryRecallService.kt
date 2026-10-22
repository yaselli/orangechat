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
    private val evidenceDAO: MemoryOrganizationDAO? = null,
) {
    // Paid embedding calls must not inherit transport retries from normal streaming chat.
    private val embedding = OpenAIProvider(
        client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(8, TimeUnit.SECONDS).build(), context,
    )
    // Manual batch indexing is not on the chat send path; do not impose its 8-second deadline.
    private val indexEmbedding = OpenAIProvider(
        client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .callTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(), context,
    )
    private val reranking = OpenAIProvider(client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).callTimeout(8, TimeUnit.SECONDS).build(), context)
    private val budget = MemoryTextBudget()
    private val queries = MemoryQueryCache()
    private val queryMutex = Mutex()
    private val indexMutex = Mutex()
    private val lastMaintenance = linkedMapOf<String, Long>()

    /** Local-only cleanup after replies. Never delete chat, evidence or memory content, or make paid calls. */
    suspend fun maintainIndex(assistant: Assistant, settings: Settings, force: Boolean = false) = indexMutex.withLock {
        val memoryScope = scope(assistant)
        val namespace = config(assistant, settings)?.key
        val key = "$memoryScope:${assistant.id}:$namespace"
        val now = System.currentTimeMillis()
        if (!force && lastMaintenance[key]?.let { now - it in 0 until 86_400_000L } == true) return@withLock
        if (namespace != null) {
            index.removeUnusedModels(memoryScope, activeNamespaces(assistant, settings))
            history.removeOtherModels(assistant.id.toString(), namespace)
        }
        index.removeStaleVectors(memoryScope)
        sources(memoryScope).forEach { index.removeStaleSource(it.id, it.revision, memoryHash(it.content)) }
        index.trimSnapshots()
        if (history.pruneInvalid(assistant.id.toString())) {
            lastMaintenance[key] = now
            if (lastMaintenance.size > 32) lastMaintenance.remove(lastMaintenance.keys.first())
        }
    }

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

    private fun activeNamespaces(assistant: Assistant, settings: Settings): List<String> =
        (settings.assistants.filter { it.enableMemory && scope(it) == scope(assistant) } + assistant)
            .mapNotNull { config(it, settings)?.key }.distinct()

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
        if (assistant.memoryIndependentHistoryRecallEnabled && pending.size < 2049) {
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

    fun embeddingNamespace(assistant: Assistant, settings: Settings): String? = config(assistant, settings)?.key

    suspend fun incrementalSlices(
        assistant: Assistant, settings: Settings, nodeIds: List<String>, attempted: Set<String>,
    ): List<MemorySlice> = withContext(Dispatchers.IO) {
        val config = config(assistant, settings) ?: return@withContext emptyList()
        val indexed = index.vectors(scope(assistant), config.key).mapTo(hashSetOf()) { it.sliceId }
        val since = assistant.memoryAutoIndexSinceMillis
        val facts = sources(scope(assistant)).filter { maxOf(it.createdAt, it.updatedAt) >= since }
        val longTerm = slices(facts).filter { it.id !in indexed && it.id !in attempted }.take(16)
        val historical = if (!assistant.memoryIndependentHistoryRecallEnabled) emptyList() else {
            val already = history.indexedIds(assistant.id.toString(), config.key)
            history.sources(assistant.id.toString(), nodeIds).filter {
                (organizationSourceMillis(it.createdAt) ?: Long.MIN_VALUE) >= since
            }.sortedBy { it.createdAt }.flatMap { historyMemorySlices(it) }
                .filter { it.id !in already && it.id !in attempted }.take(16)
        }
        longTerm + historical
    }

    suspend fun indexIncremental(
        assistant: Assistant, settings: Settings, batch: List<MemorySlice>, stillCurrent: () -> Boolean,
    ): Int = indexMutex.withLock {
        require(batch.size in 1..32)
        val config = requireNotNull(config(assistant, settings))
        check(stillCurrent())
        val vectors = embed(config, batch.map { it.text }, indexEmbedding)
        check(stillCurrent())
        val current = sources(scope(assistant)).associateBy { it.id }
        val facts = batch.zip(vectors).mapNotNull { (slice, vector) ->
            val source = current[slice.memoryId]
            if (slice.history != null || source?.assistantId != scope(assistant) || source.revision != slice.revision ||
                memoryHash(source.content) != slice.sourceHash) null
            else MemoryVectorEntity(config.key, slice.id, scope(assistant), slice.memoryId, slice.revision,
                slice.sourceHash, JsonInstant.encodeToString(vector))
        }
        index.putVectors(facts)
        if (assistant.memoryIndependentHistoryRecallEnabled) history.putValid(batch.zip(vectors).mapNotNull { (slice, vector) ->
            val ref = slice.history ?: return@mapNotNull null
            HistoryMemoryVector(config.key, slice.id, assistant.id.toString(), ref.conversationId, ref.nodeId,
                ref.messageId, slice.sourceHash, slice.start, slice.end, JsonInstant.encodeToString(vector))
        })
        batch.size
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
            index.removeUnusedModels(scope, activeNamespaces(assistant, settings))
            if (assistant.memoryIndependentHistoryRecallEnabled) history.removeOtherModels(assistant.id.toString(), config.key)
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
                if (assistant.memoryIndependentHistoryRecallEnabled) {
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
        if (!assistant.enableMemory) return@withContext MemoryRecallTurn(initialState = MemoryRecallState.DISABLED)
        if (!assistant.memoryRecallEnabled) return@withContext MemoryRecallTurn(initialState = MemoryRecallState.LEGACY)
        if (conversationId.isNullOrBlank()) return@withContext MemoryRecallTurn(initialState = MemoryRecallState.NOT_APPLIED)
        try {
            val scope = scope(assistant)
            val latest = messages.lastOrNull { it.role == MessageRole.USER }
                ?: return@withContext MemoryRecallTurn(initialState = MemoryRecallState.NOT_APPLIED)
            val hash = memoryMessageHash(latest)
            val query = memoryRecallQuery(messages, latest)
            val historyScope = assistant.id.toString()
            val boundary = if (assistant.memoryIndependentHistoryRecallEnabled) {
                history.boundary(historyScope, conversationId, messages)
            } else null
            val rerankModel = assistant.memoryRerankModelId?.let(settings::findModelById)
            val rerankIdentity = rerankModel?.let { model ->
                val provider = model.findProvider(settings.providers) as? ProviderSetting.OpenAI
                memoryHash(JsonInstant.encodeToString(model.copy(providerOverwrite = null)) + provider?.id + provider?.baseUrl)
            }.orEmpty()
            val retrievalVersion = (config(assistant, settings)?.key ?: "keyword-v1") +
                ":paired-evidence-v1:${assistant.memoryIndependentHistoryRecallEnabled}:$historyScope" +
                ":rerank:${assistant.memoryRerankEnabled}:$rerankIdentity:${assistant.memoryRerankThreshold}:${assistant.memoryRerankLimit}"
            val key = memoryHash("$conversationId\u0000$scope\u0000${latest.id}\u0000$hash" +
                "\u0000${memoryHash(query)}\u0000$retrievalVersion")
            var snapshot = index.snapshot(key)
            if (snapshot == null) {
                val result = attachEvidence(recall(assistant, settings, scope, query, sources(scope), boundary), scope, historyScope)
                index.putSnapshot(MemoryRecallSnapshotEntity(
                    key, conversationId, scope, latest.id.toString(), hash,
                    JsonInstant.encodeToString(result), System.currentTimeMillis(),
                ))
                index.trimSnapshots()
                // Concurrent retries use the first saved result, never whichever finishes last.
                snapshot = index.snapshot(key)
            }
            val result = snapshot?.let { decodeMemoryRecallSnapshot(it.excerpts) }
                ?: error("Recall snapshot was not saved")
            MemoryRecallTurn(
                scope, latest.id.toString(), result.excerpts, historyScope, boundary, result.diagnostics,
                MemoryRecallState.NOT_APPLIED,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Index errors must not block chat, fall back to injecting no recall, never the entire memory bank.
            MemoryRecallTurn(initialState = MemoryRecallState.FAILED)
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
            val evidence = turn.memoryIds().takeIf { it.isNotEmpty() }?.let { evidenceDAO?.evidenceForRecall(it, scope) }.orEmpty().associateBy { it.memoryId }
            turn.apply(messages, sources(scope).associateBy { it.id }, historical, evidence)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            turn.failed()
            messages
        }
    }

    private suspend fun attachEvidence(result: MemoryRecallSnapshot, scope: String, owner: String): MemoryRecallSnapshot {
        val memories = sources(scope).associateBy { it.id }
        val ids = result.excerpts.filter { it.history == null }.map { it.memoryId }.distinct()
        val records = ids.takeIf { it.isNotEmpty() }?.let { evidenceDAO?.evidenceForRecall(it, scope) }.orEmpty().associateBy { it.memoryId }
        val originals = if (records.isEmpty()) emptyMap() else history.sources(owner,
            records.values.map { it.nodeId }.distinct()).associateBy { it.reference.key }
        val grouped = result.excerpts.map { excerpt ->
            if (excerpt.history != null) excerpt else {
                val memory = memories[excerpt.memoryId]
                val record = records[excerpt.memoryId]
                val saved = memory?.let { verifiedRecallEvidence(it, record, originals, owner) }
                excerpt.copy(evidence = saved, evidenceState = when {
                    saved != null -> MemoryEvidenceState.VERIFIED
                    record != null -> MemoryEvidenceState.STALE
                    else -> MemoryEvidenceState.MISSING
                })
            }
        }
        val fitted = fitMemoryEvidenceGroups(grouped)
        return result.copy(excerpts = fitted, diagnostics = result.diagnostics.copy(
            selectedCount = grouped.size, budgetDropped = grouped.size - fitted.size,
            candidates = result.diagnostics.candidates.map { candidate ->
                val kept = fitted.any { it.memoryId == candidate.memoryId && it.history == candidate.history && it.text == candidate.text }
                candidate.copy(kept = kept, reason = if (candidate.kept && !kept) "记忆和完整引文超过总预算" else candidate.reason)
            },
        ))
    }

    private suspend fun recall(
        assistant: Assistant, settings: Settings, scope: String, query: String, sources: List<MemoryEntity>,
        boundary: HistoryRecallBoundary?,
    ): MemoryRecallSnapshot {
        if (query.isBlank()) return MemoryRecallSnapshot(emptyList(), MemoryRecallDiagnostics(MemoryVectorState.NO_QUERY))
        var historyKeywordsFailed = false
        var vectorState = MemoryVectorState.NOT_CONFIGURED
        val slices = slices(sources)
        val historySlices = if (boundary == null) emptyList() else try {
            history.keywords(assistant.id.toString(), query, boundary)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            historyKeywordsFailed = true
            emptyList()
        }
        val keywords = rankKeywordMemories(query, slices) + rankKeywordMemories(query, historySlices)
        val vectors = withTimeoutOrNull(8000) {
            try {
                val config = config(assistant, settings) ?: return@withTimeoutOrNull emptyList()
                val byId = slices.associateBy { it.id }
                val indexed = index.vectors(scope, config.key).filter { record ->
                    val slice = byId[record.sliceId]
                    slice != null && slice.revision == record.revision && slice.sourceHash == record.sourceHash
                }
                val historyScope = assistant.id.toString()
                var firstHistoryPage = if (boundary == null) emptyList() else {
                    history.vectors(historyScope, config.key, "", boundary)
                }
                // Do not bill for a query when every indexed historical source is already expired or stale.
                if (boundary != null) {
                    while (firstHistoryPage.isNotEmpty()) {
                        val page = firstHistoryPage
                        val originals = history.sources(historyScope, page.map { it.nodeId }.distinct())
                            .filter(boundary::allows).associateBy { it.reference.key }
                        firstHistoryPage = page.filter { record ->
                            originals["${record.conversationId}/${record.nodeId}/${record.messageId}"]?.hash == record.sourceHash
                        }
                        if (firstHistoryPage.isNotEmpty()) break
                        firstHistoryPage = history.vectors(historyScope, config.key, page.last().sliceId, boundary)
                    }
                }
                if (indexed.isEmpty() && firstHistoryPage.isEmpty()) {
                    vectorState = MemoryVectorState.NO_INDEX
                    return@withTimeoutOrNull emptyList()
                }
                val cacheKey = memoryHash(config.key + "\u0000" + scope + "\u0000" + historyScope + "\u0000" + query)
                var cached = false
                val queryVector = queryMutex.withLock {
                    queries.get(cacheKey)?.also { cached = true }
                        ?: embed(config, listOf(query)).single().also { queries.put(cacheKey, it) }
                }
                val memoryHits = indexed.mapNotNull { record ->
                    currentCoroutineContext().ensureActive()
                    val vector = MemoryVectorCodec.decode(record.vector, record.vectorBytes)?.toList()
                        ?: return@mapNotNull null
                    memoryCosine(queryVector, vector)?.let { RankedMemory(byId.getValue(record.sliceId), it) }
                }.sortedWith(compareByDescending<RankedMemory> { it.score }.thenBy { it.slice.id }).take(20)
                val historyHits = if (boundary == null) emptyList() else historyVectorHits(
                    historyScope, config.key, queryVector, firstHistoryPage, boundary,
                )
                vectorState = if (cached) MemoryVectorState.CACHED else MemoryVectorState.SUCCESS
                (memoryHits + historyHits)
                    .sortedWith(compareByDescending<RankedMemory> { it.score }.thenBy { it.slice.id })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                vectorState = MemoryVectorState.FAILED
                emptyList()
            }
        }
        if (vectors == null) vectorState = MemoryVectorState.TIMEOUT
        val longTerm = fuseMemoryRanks(keywords.filter { it.slice.history == null },
            vectors.orEmpty().filter { it.slice.history == null })
        val historical = fuseMemoryRanks(keywords.filter { it.slice.history != null },
            vectors.orEmpty().filter { it.slice.history != null })
        val currentFacts = this.sources(scope).associateBy { it.id }
        val currentHistory = historical.mapNotNull { it.slice.history?.nodeId }.distinct().chunked(64)
            .flatMap { history.sources(assistant.id.toString(), it) }.associateBy { it.reference.key }
        val validLongTerm = longTerm.filter { hit -> currentFacts[hit.slice.memoryId]?.let {
            it.revision == hit.slice.revision && memoryHash(it.content) == hit.slice.sourceHash
        } == true }
        val validHistory = historical.filter { hit -> hit.slice.history?.let { ref -> currentHistory[ref.key]?.let {
            it.hash == hit.slice.sourceHash && boundary?.allows(it) == true
        } } == true }
        val candidates = memoryRerankCandidates(query, validLongTerm, validHistory)
        val diagnostics = MemoryRecallDiagnostics(vectorState, historyKeywordsFailed,
            longTermCandidates = validLongTerm.size, historyCandidates = validHistory.size,
            )
        if (!assistant.memoryRerankEnabled) {
            // Without a relevance model, do not inject vector-only neighbours merely because they rank highest.
            val keywordIds = keywords.mapTo(hashSetOf()) { it.slice.id }
            val keywordCandidates = memoryRerankCandidates(query,
                validLongTerm.filter { it.slice.id in keywordIds }, validHistory.filter { it.slice.id in keywordIds })
            val selected = fitMemoryEvidenceGroups(keywordCandidates.map {
                SavedMemoryExcerpt(it.slice.memoryId, it.slice.revision, it.slice.sourceHash, it.text, it.slice.history)
            })
            val observed = (keywordCandidates + candidates)
                .distinctBy { it.slice.history?.role to it.text.trim() }.take(12)
            return MemoryRecallSnapshot(selected, diagnostics.copy(candidates = observed.map { hit ->
                val kept = selected.any { it.memoryId == hit.slice.memoryId && it.history == hit.slice.history && it.text == hit.text }
                MemoryCandidateTrace(hit.slice.memoryId, hit.slice.history, hit.text, kept = kept,
                    reason = if (kept) "主题关键词命中" else "无主题关键词支持或受数量、长度限制")
            }))
        }
        fun finish(state: MemoryRerankState, selected: List<SavedMemoryExcerpt> = emptyList(),
            scores: Map<Int, Double> = emptyMap()): MemoryRecallSnapshot = MemoryRecallSnapshot(selected,
            diagnostics.copy(rerank = state, candidates = candidates.mapIndexed { index, hit ->
                MemoryCandidateTrace(hit.slice.memoryId, hit.slice.history, hit.text, scores[index],
                    selected.any { it.memoryId == hit.slice.memoryId && it.history == hit.slice.history && it.text == hit.text },
                    reason = when {
                        selected.any { it.memoryId == hit.slice.memoryId && it.history == hit.slice.history && it.text == hit.text } -> "重排筛选保留"
                        state != MemoryRerankState.SUCCESS -> "重排未完成：$state"
                        scores[index] == null -> "重排未返回该候选"
                        scores.getValue(index) < assistant.memoryRerankThreshold -> "低于相关性门槛"
                        else -> "数量、去重或总长度限制"
                    })
            }))
        if (candidates.isEmpty()) return finish(MemoryRerankState.NO_CANDIDATES)
        val model = assistant.memoryRerankModelId?.let(settings::findModelById)
            ?.takeIf { it.type == ModelType.RERANK && it.customBodies.none { body ->
                body.key in setOf("model", "query", "documents", "top_n", "return_documents") } }
            ?: return finish(MemoryRerankState.NOT_CONFIGURED)
        val provider = (model.findProvider(settings.providers) as? ProviderSetting.OpenAI)?.takeIf { it.enabled }
            ?: return finish(MemoryRerankState.NOT_CONFIGURED)
        val scores = try {
            withTimeoutOrNull(8000) { reranking.rerank(provider, model, query, candidates.map {
                val ref = it.slice.history
                // Labels inform interpretation without allowing provider text to replace a saved source.
                (ref?.let { ref -> "历史原文 role=${ref.role} time=${ref.createdAt}\n" } ?: "长期记忆\n") + it.text
            }) } ?: return finish(MemoryRerankState.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { return finish(MemoryRerankState.FAILED) }
        val threshold = assistant.memoryRerankThreshold.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.5
        val selected = selectRerankedMemory(candidates, scores, threshold, assistant.memoryRerankLimit)
        return finish(MemoryRerankState.SUCCESS, selected, scores.associate { it.index to it.score })
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
            val originals = history.sources(scope, page.map { it.nodeId }.distinct())
                .filter(boundary::allows).associateBy { it.reference.key }
            for (record in page) {
                val original = originals["${record.conversationId}/${record.nodeId}/${record.messageId}"] ?: continue
                if (original.hash != record.sourceHash) continue
                currentCoroutineContext().ensureActive()
                val vector = MemoryVectorCodec.decode(record.vector, record.vectorBytes)?.toList() ?: continue
                val score = memoryCosine(query, vector) ?: continue
                if (score <= 0) continue
                best += HistoryScore(record.copy(vector = "", vectorBytes = null), score)
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
