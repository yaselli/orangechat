package me.rerere.rikkahub.data.memory

import kotlinx.coroutines.runBlocking
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
        val assistant = Assistant(enableMemory = true, memoryRecallEnabled = true, memoryEmbeddingModelId = model.id)
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI(
            baseUrl = "https://embedding.test/v1", apiKey = "test", models = listOf(model),
        )))
        val source = MemoryEntity(id = 7, assistantId = assistant.id.toString(), content = "我喜欢吃炸鸡，因为脆脆的")
        val rows = mutableListOf(source)
        val store = Store()
        val history = HistoryStore()
        var duringEmbedding: (() -> Unit)? = null
        var calls = 0
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

}
