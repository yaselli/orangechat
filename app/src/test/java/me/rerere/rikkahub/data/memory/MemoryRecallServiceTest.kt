package me.rerere.rikkahub.data.memory

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.Assistant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

class MemoryRecallServiceTest {
    private class Store : MemoryIndexDAO {
        val vectors = mutableListOf<MemoryVectorEntity>()
        val snapshots = mutableMapOf<String, MemoryRecallSnapshotEntity>()
        override suspend fun vectors(scope: String, namespace: String) = vectors.filter {
            it.scope == scope && it.namespace == namespace
        }
        override suspend fun putVectors(vectors: List<MemoryVectorEntity>) {
            for (vector in vectors) {
                this.vectors.removeAll { it.sliceId == vector.sliceId && it.namespace == vector.namespace }
                this.vectors += vector
            }
        }
        override suspend fun removeOtherModels(scope: String, namespace: String) {
            vectors.removeAll { it.scope == scope && it.namespace != namespace }
        }
        override suspend fun removeStaleVectors(scope: String) = Unit
        override suspend fun removeStaleSource(memoryId: Int, revision: Long, sourceHash: String) {
            vectors.removeAll { it.memoryId == memoryId && (it.revision != revision || it.sourceHash != sourceHash) }
        }
        override suspend fun snapshot(key: String) = snapshots[key]
        override suspend fun putSnapshot(snapshot: MemoryRecallSnapshotEntity) {
            snapshots.putIfAbsent(snapshot.key, snapshot)
        }
        override suspend fun trimSnapshots() = Unit
    }

    private class HistoryStore : HistoryMemoryStorage {
        val rows = mutableListOf<HistoryMemorySource>()
        val stored = mutableListOf<HistoryMemoryVector>()
        override suspend fun sources(scope: String, nodeIds: List<String>) = rows.filter {
            it.scope == scope && it.nodeId in nodeIds
        }
        override suspend fun page(scope: String, after: String) = rows.filter { it.scope == scope && it.nodeId > after }
            .sortedBy { it.nodeId }.take(64)
        override suspend fun boundary(scope: String, conversationId: String, messages: List<UIMessage>) =
            HistoryRecallBoundary(conversationId, 0, messages.last().createdAt.toString(),
                messages.mapTo(hashSetOf()) { it.id.toString() })
        override suspend fun keywords(scope: String, query: String, boundary: HistoryRecallBoundary) =
            rows.filter { it.scope == scope && boundary.allows(it) }.flatMap { historyMemorySlices(it) }
        override suspend fun indexedIds(scope: String, namespace: String) = stored.filter {
            it.scope == scope && it.namespace == namespace
        }.mapTo(hashSetOf()) { it.sliceId }
        override suspend fun vectors(
            scope: String, namespace: String, after: String, boundary: HistoryRecallBoundary,
        ) = stored.filter { it.scope == scope && it.namespace == namespace && it.sliceId > after }
            .sortedBy { it.sliceId }.take(64)
        override suspend fun removeOtherModels(scope: String, namespace: String) {
            stored.removeAll { it.scope == scope && it.namespace != namespace }
        }
        override suspend fun putValid(vectors: List<HistoryMemoryVector>) {
            stored += vectors.filter { record -> rows.any {
                it.scope == record.scope && it.nodeId == record.nodeId && it.messageId == record.messageId &&
                    it.hash == record.sourceHash
            } }
        }
        override suspend fun refreshKeywords(scope: String) = rows.count { it.scope == scope }
    }

    private class Fixture {
        val model = Model(modelId = "test-embedding", type = ModelType.EMBEDDING)
        val assistant = Assistant(enableMemory = true, memoryRecallEnabled = true, memoryEmbeddingModelId = model.id, memoryRetentionEnabled = false)
        val rerankModel = Model(modelId = "test-rerank", type = ModelType.RERANK)
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI(
            baseUrl = "https://embedding.test/v1", apiKey = "test", models = listOf(model, rerankModel),
        )))
        val source = MemoryEntity(id = 7, assistantId = assistant.id.toString(), content = "我喜欢吃炸鸡，因为脆脆的")
        val rows = mutableListOf(source)
        val store = Store()
        val history = HistoryStore()
        var duringEmbedding: (() -> Unit)? = null
        var calls = 0
        var rerankCalls = 0
        var rerankBody = """{"results":[{"index":0,"relevance_score":0.9}]}"""
        var rerankStatus = 200
        var onRerank: (() -> Unit)? = null
        var capturedDocuments = emptyList<String>()
        var httpStatus = 200
        val callTimeouts = mutableListOf<Long>()
        var failTransport = false
        val dao = Proxy.newProxyInstance(
            MemoryDAO::class.java.classLoader, arrayOf(MemoryDAO::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getMemoriesOfAssistant" -> rows.filter { it.assistantId == args!![0] }
                "getMemoryById" -> rows.firstOrNull { it.id == args!![0] }
                else -> error("Unexpected DAO method ${method.name}")
            }
        } as MemoryDAO
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/rerank")) {
                rerankCalls++
                val buffer = Buffer()
                chain.request().body!!.writeTo(buffer)
                val payload = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                capturedDocuments = payload.getValue("documents").jsonArray.map { it.toString() }
                assertEquals(capturedDocuments.size.toString(), payload.getValue("top_n").toString())
                assertTrue(capturedDocuments.size <= 12)
                onRerank?.invoke()
                return@addInterceptor Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(rerankStatus).message("rerank").body(rerankBody.toResponseBody("application/json".toMediaType())).build()
            }
            calls++
            callTimeouts += chain.call().timeout().timeoutNanos()
            if (failTransport) throw IOException("offline")
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val input = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("input")
            val count = if (input is kotlinx.serialization.json.JsonArray) input.jsonArray.size else 1
            duringEmbedding?.invoke()
            val body = """{"model":"test-embedding","data":[""" + (0 until count).joinToString(",") {
                """{"index":$it,"embedding":[1,0]}"""
            } + "]}"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(httpStatus).message("response")
                .body((if (httpStatus == 200) body else """{"error":{"message":"rate limited"}}""")
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val service = MemoryRecallService(dao, store, client, history = history)
    }

    @Test
    fun `recorded successful recall survives retry snapshot reuse without another paid query`() = runBlocking {
        val f = Fixture()
        f.service.buildIndex(f.assistant, f.settings)
        val user = UIMessage.user("炸鸡")
        val turn = f.service.prepare(f.assistant, f.settings, "chat", listOf(user))
        f.service.apply(turn, listOf(user))
        val first = requireNotNull(turn.annotate(UIMessage.assistant("answer")).memoryRecallTrace())
        assertEquals(MemoryRecallState.USED, first.state)
        assertEquals(MemoryVectorState.SUCCESS, first.diagnostics.vector)
        assertEquals(2, f.calls)
        val reused = f.service.prepare(f.assistant, f.settings, "chat", listOf(user))
        f.service.apply(reused, listOf(user))
        assertEquals(first, reused.annotate(UIMessage.assistant("answer")).memoryRecallTrace())
        assertEquals(2, f.calls)
    }

    @Test
    fun `failed vector query with keyword fallback is recorded as degraded rather than successful vector search`() = runBlocking {
        val f = Fixture()
        f.service.buildIndex(f.assistant, f.settings)
        f.failTransport = true
        val user = UIMessage.user("炸鸡")
        val turn = f.service.prepare(f.assistant, f.settings, "chat", listOf(user))
        f.service.apply(turn, listOf(user))
        val trace = requireNotNull(turn.annotate(UIMessage.assistant("answer")).memoryRecallTrace())
        assertEquals(MemoryRecallState.USED, trace.state)
        assertEquals(MemoryVectorState.FAILED, trace.diagnostics.vector)
        assertTrue(trace.degraded)
        assertEquals(2, f.calls)
    }

    @Test
    fun `manual batch indexing has longer deadline while chat retrieval remains eight seconds`() = runBlocking {
        val f = Fixture()
        assertEquals(1, f.service.buildIndex(f.assistant, f.settings))
        f.service.prepare(f.assistant, f.settings, "chat", listOf(UIMessage.user("炸鸡")))
        assertEquals(listOf(60_000_000_000L, 8_000_000_000L), f.callTimeouts)
    }

    @Test
    fun `HTTP indexing failure reports status without automatic retries or saved invalid vectors`() = runBlocking {
        val f = Fixture()
        f.httpStatus = 429
        try {
            f.service.buildIndex(f.assistant, f.settings)
            fail("Must propagate interface failure")
        } catch (failure: IllegalStateException) {
            assertTrue(memoryIndexFailureReason(failure).contains("HTTP 429"))
        }
        assertEquals(1, f.calls)
        assertTrue(f.store.vectors.isEmpty())
        assertEquals(listOf(f.source), f.rows)
    }

    @Test
    fun `manual indexing is incremental and a tool loop never repeats paid retrieval`() = runBlocking {
        val f = Fixture()
        assertEquals(MemoryRecallService.IndexPlan(1, 1), f.service.indexPlan(f.assistant, f.settings))
        assertEquals(1, f.service.buildIndex(f.assistant, f.settings))
        assertEquals(1, f.calls)
        assertEquals(0, f.service.buildIndex(f.assistant, f.settings))
        assertEquals(1, f.calls)
        val user = UIMessage.user("炸鸡")
        val turn = f.service.prepare(f.assistant, f.settings, "chat", listOf(user))
        assertEquals(2, f.calls)
        val first = f.service.apply(turn, listOf(user))
        val later = f.service.apply(turn, listOf(user, UIMessage.assistant("工具结果")))
        assertEquals(first.first(), later.first())
        assertEquals(2, f.calls)
        f.service.prepare(f.assistant, f.settings, "chat", listOf(user))
        assertEquals(2, f.calls)
        // Same embedding query in a distinct new turn uses the bounded local query-vector cache.
        f.service.prepare(f.assistant, f.settings, "other-chat", listOf(UIMessage.user("炸鸡")))
        assertEquals(2, f.calls)
        f.rows.clear()
        assertEquals(user, f.service.apply(turn, listOf(user)).single())
    }

    @Test
    fun `no index means keyword only with zero paid calls and isolated assistants`() = runBlocking {
        val f = Fixture()
        val user = UIMessage.user("炸鸡")
        val turn = f.service.prepare(f.assistant, f.settings, "chat", listOf(user))
        assertEquals(2, f.service.apply(turn, listOf(user)).single().parts.size)
        assertEquals(0, f.calls)
        val other = f.assistant.copy(id = kotlin.uuid.Uuid.random())
        val isolated = f.service.prepare(other, f.settings, "other", listOf(user))
        assertEquals(user, f.service.apply(isolated, listOf(user)).single())
        assertEquals(0, f.calls)
    }

    @Test
    fun `embedding failures fall back once without retries or whole-bank injection`() = runBlocking {
        val f = Fixture()
        f.service.buildIndex(f.assistant, f.settings)
        f.failTransport = true
        val user = UIMessage.user("炸鸡")
        val turn = f.service.prepare(f.assistant, f.settings, "chat", listOf(user))
        assertEquals(2, f.calls)
        assertEquals(2, f.service.apply(turn, listOf(user)).single().parts.size)
        f.service.prepare(f.assistant, f.settings, "chat", listOf(user))
        assertEquals(2, f.calls)
    }

    @Test
    fun `source changes after confirmation cannot increase approved indexing calls`() = runBlocking {
        val f = Fixture()
        val plan = f.service.indexPlan(f.assistant, f.settings)
        f.rows += f.source.copy(id = 8, content = "new source")
        try {
            f.service.buildIndex(f.assistant, f.settings, maxChunks = plan.pendingChunks)
            fail("Should require a fresh plan")
        } catch (_: IllegalStateException) { }
        assertEquals(0, f.calls)
        assertTrue(f.store.vectors.isEmpty())
    }

    @Test
    fun `disabled memory produces no snapshot and no paid calls`() = runBlocking {
        val f = Fixture()
        val user = UIMessage.user("炸鸡")
        val turn = f.service.prepare(f.assistant.copy(enableMemory = false), f.settings, "chat", listOf(user))
        assertEquals(user, f.service.apply(turn, listOf(user)).single())
        assertEquals(0, f.calls)
        assertTrue(f.store.snapshots.isEmpty())
    }

    @Test
    fun `corrupt snapshots omit derived recall without modifying the conversation`() = runBlocking {
        val f = Fixture()
        val user = UIMessage.user("炸鸡")
        f.service.prepare(f.assistant, f.settings, "chat", listOf(user))
        f.store.snapshots.replaceAll { _, value -> value.copy(excerpts = "broken-json") }
        val turn = f.service.prepare(f.assistant, f.settings, "chat", listOf(user))
        assertEquals(user, f.service.apply(turn, listOf(user)).single())
        assertEquals(0, f.calls)
    }
    @Test
    fun `history keyword fallback costs nothing and uses the current assistant even with global memories`() = runBlocking {
        val f = Fixture()
        val assistant = f.assistant.copy(memoryHistoryRecallEnabled = true, useGlobalMemory = true)
        val source = HistoryMemorySource("past", "node", "message", assistant.id.toString(), "user",
            "2020-01-01T00:00", "只喝燕麦牛奶", 0)
        f.history.rows += source
        f.history.rows += source.copy(conversationId = "other", nodeId = "other-node", scope = "other-assistant")
        val user = UIMessage.user("燕麦牛奶")
        val turn = f.service.prepare(assistant, f.settings, "chat", listOf(user))
        val request = f.service.apply(turn, listOf(user)).single()
        assertEquals(0, f.calls)
        assertEquals(2, request.parts.size)
        val text = (request.parts.last() as me.rerere.ai.ui.UIMessagePart.Text).text
        assertTrue(text.contains("conversation=past"))
        assertFalse(text.contains("conversation=other"))
        f.history.rows.clear()
        assertEquals(user, f.service.apply(turn, listOf(user)).single())
    }

    @Test
    fun `memory and history vectors share one paid query and revalidate sources on tool steps`() = runBlocking {
        val f = Fixture()
        val assistant = f.assistant.copy(memoryHistoryRecallEnabled = true)
        f.history.rows += HistoryMemorySource("past", "node", "message", assistant.id.toString(), "user",
            "2020-01-01T00:00", "炸鸡一定要脆脆的", 0)
        assertEquals(MemoryRecallService.IndexPlan(2, 1), f.service.indexPlan(assistant, f.settings))
        f.service.buildIndex(assistant, f.settings)
        assertEquals(1, f.calls)
        assertEquals(1, f.history.stored.size)
        val user = UIMessage.user("炸鸡")
        val turn = f.service.prepare(assistant, f.settings, "chat", listOf(user))
        val first = f.service.apply(turn, listOf(user)).single()
        assertTrue((first.parts.last() as me.rerere.ai.ui.UIMessagePart.Text).text.contains("[history user"))
        assertEquals(2, f.calls)
        f.history.rows.clear()
        val next = f.service.apply(turn, listOf(user, UIMessage.assistant("继续")))
        assertFalse((next.first().parts.last() as me.rerere.ai.ui.UIMessagePart.Text).text.contains("[history user"))
        assertEquals(2, f.calls)
    }

    @Test
    fun `late embedding response cannot recreate a removed history source`() = runBlocking {
        val f = Fixture()
        val assistant = f.assistant.copy(memoryHistoryRecallEnabled = true)
        f.history.rows += HistoryMemorySource("past", "node", "message", assistant.id.toString(), "user",
            "2020-01-01T00:00", "燕麦牛奶", 0)
        f.duringEmbedding = { f.history.rows.clear() }
        f.service.buildIndex(assistant, f.settings)
        assertTrue(f.history.stored.isEmpty())
        assertEquals(1, f.calls)
    }

    @Test
    fun `turn snapshots invalidate on history toggle without reusing another assistants history`() = runBlocking {
        val f = Fixture()
        val assistant = f.assistant.copy(memoryHistoryRecallEnabled = true)
        f.history.rows += HistoryMemorySource("past", "node", "message", assistant.id.toString(), "assistant",
            "2020-01-01T00:00", "燕麦牛奶", 0)
        val user = UIMessage.user("燕麦牛奶")
        val turn = f.service.prepare(assistant, f.settings, "chat", listOf(user))
        assertEquals(2, f.service.apply(turn, listOf(user)).single().parts.size)
        val disabled = f.service.prepare(assistant.copy(memoryHistoryRecallEnabled = false), f.settings, "chat", listOf(user))
        assertEquals(user, f.service.apply(disabled, listOf(user)).single())
        val other = f.service.prepare(assistant.copy(id = kotlin.uuid.Uuid.random()), f.settings, "chat", listOf(user))
        assertEquals(user, f.service.apply(other, listOf(user)).single())
        assertEquals(0, f.calls)
    }

    private fun rerankAssistant(f: Fixture) = f.assistant.copy(memoryRerankEnabled = true,
        memoryRerankModelId = f.rerankModel.id, memoryHistoryRecallEnabled = true)

    @Test
    fun `two independent routes send both roles through one rerank and reuse the paid snapshot`() = runBlocking {
        val f = Fixture()
        val assistant = rerankAssistant(f)
        f.history.rows += HistoryMemorySource("old", "n1", "m1", assistant.id.toString(), "user", "2026-01-01T00:00", "我想吃辣火锅", 0)
        f.history.rows += HistoryMemorySource("old", "n2", "m2", assistant.id.toString(), "assistant", "2026-01-01T00:01", "我记得你说想吃辣火锅", 1)
        f.rerankBody = """{"results":[{"index":0,"relevance_score":0.9},{"index":1,"relevance_score":0.8},{"index":2,"relevance_score":0.1}]}"""
        val messages = listOf(UIMessage.user("炸鸡辣火锅"))
        val turn = f.service.prepare(assistant, f.settings, "chat", messages)
        f.service.apply(turn, messages)
        val trace = requireNotNull(turn.annotate(UIMessage.assistant("answer")).memoryRecallTrace())
        assertEquals(MemoryRecallState.USED, trace.state)
        assertEquals(MemoryRerankState.SUCCESS, trace.diagnostics.rerank)
        assertEquals(3, trace.diagnostics.candidates.size)
        assertEquals(2, trace.diagnostics.candidates.count { it.kept })
        assertTrue(f.capturedDocuments.any { it.contains("长期记忆") })
        assertTrue(f.capturedDocuments.any { it.contains("role=user") })
        assertTrue(f.capturedDocuments.any { it.contains("role=assistant") })
        assertEquals(1, f.rerankCalls)
        f.service.prepare(assistant, f.settings, "chat", messages)
        assertEquals(1, f.rerankCalls)
        assertEquals(0, f.calls) // No vector index, keywords still provide rerank candidates.
    }

    @Test
    fun `all low rerank scores produce no forced reference and no unfiltered fallback`() = runBlocking {
        val f = Fixture()
        f.rerankBody = """{"results":[{"index":0,"relevance_score":0.1}]}"""
        val messages = listOf(UIMessage.user("炸鸡"))
        val turn = f.service.prepare(rerankAssistant(f), f.settings, "chat", messages)
        assertEquals(messages, f.service.apply(turn, messages))
        val trace = requireNotNull(turn.annotate(UIMessage.assistant("answer")).memoryRecallTrace())
        assertEquals(MemoryRecallState.NO_MATCH, trace.state)
        assertEquals(MemoryRerankState.SUCCESS, trace.diagnostics.rerank)
        assertFalse(trace.diagnostics.candidates.single().kept)
        assertEquals(1, f.rerankCalls)
    }

    @Test
    fun `rerank failure invalid indices and missing configuration stay visible and never retry`() = runBlocking {
        for (body in listOf("bad response", """{"results":[{"index":99,"relevance_score":0.9}]}""")) {
            val f = Fixture()
            f.rerankBody = body
            val messages = listOf(UIMessage.user("炸鸡"))
            val turn = f.service.prepare(rerankAssistant(f), f.settings, "chat", messages)
            assertEquals(messages, f.service.apply(turn, messages))
            val trace = requireNotNull(turn.annotate(UIMessage.assistant("answer")).memoryRecallTrace())
            assertEquals(MemoryRecallState.FAILED, trace.state)
            assertEquals(MemoryRerankState.FAILED, trace.diagnostics.rerank)
            f.service.prepare(rerankAssistant(f), f.settings, "chat", messages)
            assertEquals(1, f.rerankCalls)
        }
        val f = Fixture()
        val messages = listOf(UIMessage.user("炸鸡"))
        val turn = f.service.prepare(rerankAssistant(f).copy(memoryRerankModelId = null), f.settings, "chat", messages)
        f.service.apply(turn, messages)
        assertEquals(MemoryRerankState.NOT_CONFIGURED,
            turn.annotate(UIMessage.assistant("answer")).memoryRecallTrace()?.diagnostics?.rerank)
        assertEquals(0, f.rerankCalls)
    }

    @Test
    fun `forgetting excludes old keywords and vectors while long term memory remains available`() = runBlocking {
        val f = Fixture()
        val assistant = f.assistant.copy(memoryHistoryRecallEnabled = true, memoryRetentionEnabled = true)
        f.history.rows += HistoryMemorySource("old", "n1", "m1", assistant.id.toString(), "user", "2020-01-01T00:00", "炸鸡", 0)
        assertEquals(1, f.service.buildIndex(assistant, f.settings)) // The expired history is not embedded.
        val messages = listOf(UIMessage.user("炸鸡"))
        val turn = f.service.prepare(assistant, f.settings, "chat", messages)
        f.service.apply(turn, messages)
        val trace = requireNotNull(turn.annotate(UIMessage.assistant("answer")).memoryRecallTrace())
        assertTrue(trace.excerpts.isNotEmpty())
        assertTrue(trace.excerpts.all { it.history == null })
        assertEquals(0, trace.diagnostics.historyCandidates)
    }

    private class Journal : MemoryAutoIndexDAO {
        val rows = mutableListOf<MemoryIndexAttempt>()
        val latestValue = MutableStateFlow<MemoryIndexAttempt?>(null)
        override suspend fun attempted(owner: String, namespace: String) = rows.filter {
            it.owner == owner && it.namespace == namespace }.map { it.sliceId }
        override suspend fun batchesSince(owner: String, since: Long) = rows.filter {
            it.owner == owner && it.createdAt >= since }.map { it.batchId }.distinct().size
        override suspend fun claim(rows: List<MemoryIndexAttempt>) = rows.map { row ->
            if (this.rows.any { it.owner == row.owner && it.namespace == row.namespace && it.sliceId == row.sliceId }) -1L
            else { this.rows += row; latestValue.value = row; this.rows.size.toLong() }
        }
        override suspend fun finish(owner: String, batchId: String, state: String, detail: String) {
            rows.replaceAll { if (it.owner == owner && it.batchId == batchId) it.copy(state = state, detail = detail) else it }
            latestValue.value = rows.lastOrNull()
        }
        override fun latest(owner: String) = latestValue
    }

    @Test
    fun `automatic indexing is incremental does not backfill old rows and failed batches count toward daily cap`() = runBlocking {
        val f = Fixture()
        val journal = Journal()
        val assistant = f.assistant.copy(memoryAutoIndexEnabled = true, memoryAutoIndexSinceMillis = 1,
            memoryAutoIndexDailyLimit = 1)
        val settings = f.settings.copy(assistants = listOf(assistant))
        val auto = MemoryAutoIndexService(journal, { settings }, f.service)
        auto.afterReply(assistant.id, emptyList()) // Legacy rows have no new creation/update timestamp.
        assertEquals(0, f.calls)
        f.rows[0] = f.source.copy(updatedAt = System.currentTimeMillis())
        f.failTransport = true
        auto.afterReply(assistant.id, emptyList())
        assertEquals(1, f.calls)
        assertTrue(journal.rows.all { it.state == "failed" })
        f.failTransport = false
        f.rows += f.source.copy(id = 8, content = "新记忆", createdAt = System.currentTimeMillis())
        auto.afterReply(assistant.id, emptyList())
        assertEquals(1, f.calls) // A failed request consumes the day's one automatic batch.
        assertTrue(f.store.vectors.isEmpty())
    }

    @Test
    fun `automatic indexing includes both roles once and respects settings changes during request`() = runBlocking {
        val f = Fixture()
        val journal = Journal()
        val assistant = f.assistant.copy(memoryAutoIndexEnabled = true, memoryAutoIndexSinceMillis = 1,
            memoryHistoryRecallEnabled = true)
        val now = java.time.LocalDateTime.now().toString()
        f.history.rows += HistoryMemorySource("chat", "n1", "m1", assistant.id.toString(), "user", now, "她说的话", 0)
        f.history.rows += HistoryMemorySource("chat", "n2", "m2", assistant.id.toString(), "assistant", now, "我说的话", 1)
        var settings = f.settings.copy(assistants = listOf(assistant))
        val auto = MemoryAutoIndexService(journal, { settings }, f.service)
        auto.afterReply(assistant.id, listOf("n1", "n2"))
        auto.afterReply(assistant.id, listOf("n1", "n2"))
        assertEquals(1, f.calls)
        assertEquals(2, f.history.stored.size)
        f.rows[0] = f.source.copy(updatedAt = System.currentTimeMillis())
        f.duringEmbedding = { settings = settings.copy(assistants = listOf(assistant.copy(memoryAutoIndexEnabled = false))) }
        auto.afterReply(assistant.id, listOf("n1", "n2"))
        assertEquals(2, f.calls)
        assertTrue(f.store.vectors.isEmpty())
        assertEquals("failed", journal.rows.last().state)
    }

}
