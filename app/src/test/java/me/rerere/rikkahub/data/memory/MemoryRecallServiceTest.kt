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

    private class Fixture {
        val model = Model(modelId = "test-embedding", type = ModelType.EMBEDDING)
        val assistant = Assistant(enableMemory = true, memoryRecallEnabled = true, memoryEmbeddingModelId = model.id)
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI(
            baseUrl = "https://embedding.test/v1", apiKey = "test", models = listOf(model),
        )))
        val source = MemoryEntity(id = 7, assistantId = assistant.id.toString(), content = "我喜欢吃炸鸡，因为脆脆的")
        val rows = mutableListOf(source)
        val store = Store()
        var calls = 0
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
            if (failTransport) throw IOException("offline")
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val input = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("input")
            val count = if (input is kotlinx.serialization.json.JsonArray) input.jsonArray.size else 1
            val body = """{"model":"test-embedding","data":[""" + (0 until count).joinToString(",") {
                """{"index":$it,"embedding":[1,0]}"""
            } + "]}"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val service = MemoryRecallService(dao, store, client)
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
}
