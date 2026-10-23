package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

class UsageRecorderTest {
    private val setting = ProviderSetting.OpenAI()
    private val params = TextGenerationParams(Model(modelId = "api-model-name"))
    private val messages = listOf(UIMessage.user("private request body"))
    private val tag = UsageTag(UsageSource.PROACTIVE, Uuid.random(), Uuid.random())

    @Test
    fun `stream snapshots write one request with source identity and do not change the provider stream`() = runBlocking {
        withFixture { fixture ->
            val usage = TokenUsage(20, 5, 8, 25)
            val chunks = listOf(chunk(TokenUsage(promptTokens = 20)), chunk(null), chunk(usage), chunk(usage))
            val delegate = FakeProvider(chunks)
            val provider = fixture.recorder.wrap(delegate, tag)
            assertEquals(chunks, provider.streamText(setting, messages, params).toList())
            fixture.awaitWrites()
            val row = fixture.records.single()
            assertEquals(20, row.promptTokens)
            assertEquals(5, row.completionTokens)
            assertEquals(8, row.cachedTokens)
            assertEquals(tag.source.name, row.source)
            assertEquals(tag.assistantId.toString(), row.assistantId)
            assertEquals(tag.conversationId.toString(), row.conversationId)
            assertEquals(setting.id.toString(), row.providerId)
            assertEquals(params.model.id.toString(), row.modelId)
            assertEquals("api-model-name", row.modelName)
            assertEquals(123L, row.createdAt)
            assertEquals(1, delegate.streamCalls)
            assertSame(messages, delegate.requestMessages)
            assertSame(params, delegate.requestParams)
        }
    }

    @Test
    fun `tool turns and retries are separate requests rather than a cumulative row`() = runBlocking {
        withFixture { fixture ->
            val provider = fixture.recorder.wrap(FakeProvider(listOf(chunk(TokenUsage(20, 5, 8, 25)))), tag)
            repeat(3) { provider.streamText(setting, messages, params).toList() }
            fixture.awaitWrites()
            assertEquals(3, fixture.records.size)
            assertEquals(60, fixture.records.sumOf { it.promptTokens })
        }
    }

    @Test
    fun `partial usage survives stream failure and collector cancellation while the error propagates`() = runBlocking {
        for (failure in listOf(IllegalStateException("failed"), CancellationException("cancelled"))) {
            withFixture { fixture ->
                val provider = fixture.recorder.wrap(FakeProvider(listOf(chunk(TokenUsage(20, 1, 8, 21))), failure), tag)
                try {
                    provider.streamText(setting, messages, params).toList()
                    fail("Failure must propagate")
                } catch (actual: Exception) {
                    assertSame(failure, actual)
                }
                fixture.awaitWrites()
                assertEquals(20, fixture.records.single().promptTokens)
            }
        }
        withFixture { fixture ->
            val provider = fixture.recorder.wrap(FakeProvider(listOf(
                chunk(TokenUsage(20, 1, 8, 21)), chunk(TokenUsage(20, 10, 8, 30)),
            )), tag)
            val stop = CancellationException("user stopped")
            try {
                provider.streamText(setting, messages, params).collect { throw stop }
                fail("Cancellation must propagate")
            } catch (actual: CancellationException) {
                assertSame(stop, actual)
            }
            fixture.awaitWrites()
            assertEquals(1, fixture.records.single().completionTokens)
        }
    }

    @Test
    fun `nonstream request writes once and database failure cannot fail the model response`() = runBlocking {
        for (failWrite in listOf(false, true)) {
            withFixture(failWrite) { fixture ->
                val response = chunk(TokenUsage(20, 5, 8, 25))
                val delegate = FakeProvider(listOf(response))
                val provider = fixture.recorder.wrap(delegate, UsageTag(UsageSource.TRANSLATION))
                assertSame(response, provider.generateText(setting, messages, params))
                fixture.awaitWrites()
                assertEquals(1, delegate.generateCalls)
                assertEquals(if (failWrite) 0 else 1, fixture.records.size)
            }
        }
    }

    @Test
    fun `missing or zero usage does not invent records and failed nonstream calls do not invent a charge`() = runBlocking {
        withFixture { fixture ->
            for (usage in listOf(null, TokenUsage())) {
                val provider = fixture.recorder.wrap(FakeProvider(listOf(chunk(usage))), tag)
                provider.streamText(setting, messages, params).toList()
                provider.generateText(setting, messages, params)
            }
            val failure = IllegalStateException("request rejected")
            try {
                fixture.recorder.wrap(FakeProvider(emptyList(), failure), tag).generateText(setting, messages, params)
                fail("Failure must propagate")
            } catch (actual: IllegalStateException) {
                assertSame(failure, actual)
            }
            fixture.awaitWrites()
            assertTrue(fixture.records.isEmpty())
        }
    }

    private suspend fun withFixture(failWrite: Boolean = false, block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(failWrite)
        try { block(fixture) } finally { fixture.scope.cancel() }
    }

    private class Fixture(failWrite: Boolean) {
        val records = CopyOnWriteArrayList<UsageRecordEntity>()
        val scope = CoroutineScope(SupervisorJob())
        // Only insert is exercised here; aggregate SQL is covered against real Room on Android.
        private val dao = Proxy.newProxyInstance(
            UsageRecordDAO::class.java.classLoader, arrayOf(UsageRecordDAO::class.java),
        ) { _, method, args ->
            check(method.name == "insert") { "Unexpected DAO method: ${method.name}" }
            if (failWrite) error("database unavailable")
            records.add(args!![0] as UsageRecordEntity)
            Unit
        } as UsageRecordDAO
        val recorder = UsageRecorder(dao, scope) { 123L }
        suspend fun awaitWrites() { scope.coroutineContext[Job]!!.children.toList().joinAll() }
    }

    private fun chunk(usage: TokenUsage?) = MessageChunk("chunk", "model", emptyList(), usage)

    private class FakeProvider(
        private val chunks: List<MessageChunk>,
        private val failure: Exception? = null,
    ) : Provider<ProviderSetting> {
        var streamCalls = 0
        var generateCalls = 0
        var requestMessages: List<UIMessage>? = null
        var requestParams: TextGenerationParams? = null
        override suspend fun streamText(
            providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams,
        ): Flow<MessageChunk> {
            streamCalls++
            requestMessages = messages
            requestParams = params
            return flow {
                chunks.forEach { emit(it) }
                failure?.let { throw it }
            }
        }
        override suspend fun generateText(
            providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams,
        ): MessageChunk {
            generateCalls++
            failure?.let { throw it }
            return chunks.single()
        }
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = error("unused")
        override suspend fun generateImage(
            providerSetting: ProviderSetting, params: ImageGenerationParams,
        ): ImageGenerationResult = error("unused")
    }
}
