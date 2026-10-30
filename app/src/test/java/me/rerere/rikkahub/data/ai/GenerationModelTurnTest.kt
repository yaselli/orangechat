package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.service.beginProactiveAssistantTurn
import me.rerere.rikkahub.data.service.ProactiveProviderRequest
import org.junit.Assert.*
import org.junit.Test

class GenerationModelTurnTest {
    private val model = Model()
    private val setting = ProviderSetting.OpenAI()
    private val params = TextGenerationParams(model)

    @Test
    fun `stream preserves reasoning and tool deltas with one local reply id`() = runBlocking {
        val tool = UIMessagePart.Tool("call_1", "example", "{\"value\":")
        val provider = RecordingProvider(listOf(
            chunk(UIMessagePart.Reasoning("thinking")),
            chunk(UIMessagePart.Text("reply")),
            chunk(tool),
            chunk(tool.copy(input = "1}")),
        ))
        val history = listOf(UIMessage.user("question"))
        val updates = mutableListOf<List<UIMessage>>()

        val result = turn(provider, history, history, updates = updates)

        assertEquals(4, updates.size)
        assertEquals(1, updates.map { it.last().id }.distinct().size)
        assertEquals(history.single(), result.first())
        assertEquals(model.id, result.last().modelId)
        assertEquals("thinking", result.last().parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals("reply", result.last().parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals("call_1", result.last().getTools().single().toolCallId)
        assertEquals("{\"value\":1}", result.last().getTools().single().input)
        assertSame(history, provider.requestMessages)
        assertSame(setting, provider.setting)
        assertSame(params, provider.params)
        assertEquals(1, provider.streamCalls)
        assertEquals(0, provider.generateCalls)
    }

    @Test
    fun `proactive seed stays local and cannot overwrite old assistant`() = runBlocking {
        val history = listOf(UIMessage.user("question"), UIMessage.assistant("old reply"))
        val local = beginProactiveAssistantTurn(history, model.id)
        val provider = RecordingProvider(listOf(chunk(UIMessagePart.Text("[WAIT:20]"))))

        val result = turn(provider, history, local, mergeUsage = false)

        assertSame(history, provider.requestMessages)
        assertEquals(history, result.dropLast(1))
        assertEquals(local.last().id, result.last().id)
        assertNotEquals(history.last().id, result.last().id)
        assertEquals("[WAIT:20]", result.last().toText())
    }

    @Test
    fun `Gemini background event reaches provider without entering local generated history`() = runBlocking {
        val history = listOf(UIMessage.user("question"), UIMessage.assistant("old reply"))
        val local = beginProactiveAssistantTurn(history, model.id)
        val request = ProactiveProviderRequest.create(history, "gemini-3.8-flash", false)!!.applyTo(history)
        val provider = RecordingProvider(listOf(chunk(UIMessagePart.Text("[WAIT:20]"))))

        val result = generateModelTurn(
            providerImpl = provider,
            providerSetting = setting,
            requestMessages = request,
            initialMessages = local,
            params = params,
            model = model,
            stream = true,
            mergeUsage = false,
            onUpdateMessages = {},
        )

        assertEquals(MessageRole.USER, provider.requestMessages!!.last().role)
        assertEquals(history, result.dropLast(1))
        assertEquals(local.last().id, result.last().id)
        assertEquals("[WAIT:20]", result.last().toText())
        assertEquals(1, result.count { it.role == MessageRole.USER })
        assertTrue(result.none { it.id == request.last().id })
    }

    @Test
    fun `usage only tail updates chat usage without losing reply`() = runBlocking {
        val provider = RecordingProvider(listOf(
            chunk(UIMessagePart.Text("reply")).copy(usage = TokenUsage(promptTokens = 10)),
            MessageChunk("usage", "model", emptyList(), TokenUsage(completionTokens = 3)),
        ))
        val history = listOf(UIMessage.user("question"))
        val result = turn(provider, history, history)
        assertEquals("reply", result.last().toText())
        assertEquals(TokenUsage(promptTokens = 10, completionTokens = 3, totalTokens = 13), result.last().usage)
    }

    @Test
    fun `tool turns add requests but repeated cumulative snapshots count once`() = runBlocking {
        val oldUsage = TokenUsage(10, 3, 4, 13)
        val history = listOf(UIMessage.user("question"), UIMessage.assistant("tool result").copy(usage = oldUsage))
        val usage = TokenUsage(20, 5, 6, 25)
        val provider = RecordingProvider(listOf(
            chunk(UIMessagePart.Text("answer")).copy(usage = TokenUsage(promptTokens = 20)),
            MessageChunk("usage", "model", emptyList(), usage),
            MessageChunk("usage", "model", emptyList(), usage),
        ))
        val result = turn(provider, history, history)
        assertEquals(history.last().id, result.last().id)
        assertEquals(TokenUsage(30, 8, 10, 38), result.last().usage)

        val next = RecordingProvider(listOf(
            chunk(UIMessagePart.Text("next")).copy(usage = TokenUsage(2, 1, 0, 3)),
        ))
        assertEquals(TokenUsage(32, 9, 10, 41), turn(next, result, result).last().usage)
        assertEquals(oldUsage, history.last().usage)
    }

    @Test
    fun `new assistant does not inherit usage from earlier answers or user messages`() = runBlocking {
        val history = listOf(
            UIMessage.assistant("old").copy(usage = TokenUsage(100, 50, 0, 150), generationTimeMs = 90_000),
            UIMessage.user("next").copy(usage = TokenUsage(7, 1, 0, 8)),
        )
        val provider = RecordingProvider(listOf(
            MessageChunk("usage", "model", emptyList(), TokenUsage(promptTokens = 10)),
            chunk(UIMessagePart.Text("new")).copy(usage = TokenUsage(completionTokens = 2)),
        ))
        val result = turn(provider, history, history)
        assertEquals(history, result.dropLast(1))
        assertEquals(TokenUsage(10, 2, 0, 12), result.last().usage)
        assertEquals(0L, result.last().generationTimeMs)
    }

    @Test
    fun `stream timing excludes first token wait and update callbacks and adds earlier generation`() = runBlocking {
        var nowMs = 100_000L // Includes tool/approval time before this model request.
        val history = listOf(UIMessage.assistant("tool result").copy(generationTimeMs = 400))
        val provider = RecordingProvider(
            chunks = listOf(chunk(UIMessagePart.Text("a")), chunk(UIMessagePart.Text("b"))),
            beforeEmit = { index -> nowMs += if (index == 0) 5_000 else 1_000 },
            onComplete = { nowMs += 500 },
        )
        val updates = mutableListOf<List<UIMessage>>()
        val result = generateModelTurn(
            provider, setting, history, history, params, model, true, true,
            nanoTime = { nowMs * 1_000_000 },
        ) {
            updates.add(it)
            nowMs += 200 // Rendering/persistence must not inflate model generation time.
        }
        assertEquals(1_900L, result.last().generationTimeMs)
        assertEquals(result, updates.last())
        assertNull(provider.requestMessages!!.last().generationTimeMs)
        assertEquals(400L, history.last().generationTimeMs)
    }

    @Test
    fun `nonstream timing uses request duration because first token is unavailable`() = runBlocking {
        var nowMs = 0L
        val provider = RecordingProvider(
            listOf(chunk(UIMessagePart.Text("reply"))), onComplete = { nowMs += 750 },
        )
        val history = listOf(UIMessage.user("question"))
        val result = generateModelTurn(
            provider, setting, history, history, params, model, false, true,
            nanoTime = { nowMs * 1_000_000 },
        ) { nowMs += 100 }
        assertEquals(750L, result.last().generationTimeMs)
    }

    @Test
    fun `proactive usage policy is preserved`() = runBlocking {
        val provider = RecordingProvider(listOf(
            chunk(UIMessagePart.Text("reply")).copy(usage = TokenUsage(promptTokens = 10)),
        ))
        val history = listOf(UIMessage.user("question"))
        assertNull(turn(provider, history, history, mergeUsage = false).last().usage)
    }

    @Test
    fun `nonstream turn uses generate endpoint and delivers one update`() = runBlocking {
        val provider = RecordingProvider(listOf(chunk(UIMessagePart.Text("reply"))))
        val history = listOf(UIMessage.user("question"))
        val updates = mutableListOf<List<UIMessage>>()
        val result = turn(provider, history, history, stream = false, updates = updates)
        assertEquals("reply", result.last().toText())
        assertEquals(listOf(result), updates)
        assertEquals(0, provider.streamCalls)
        assertEquals(1, provider.generateCalls)
    }

    @Test
    fun `empty stream retains local state without publishing a fabricated response`() = runBlocking {
        val provider = RecordingProvider(emptyList())
        val history = listOf(UIMessage.user("question"))
        val updates = mutableListOf<List<UIMessage>>()
        assertSame(history, turn(provider, history, history, updates = updates))
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `API failure propagates after the last real update`() = runBlocking {
        val failure = IllegalStateException("API failed")
        val provider = RecordingProvider(listOf(chunk(UIMessagePart.Text("partial"))), failure)
        val history = listOf(UIMessage.user("question"))
        val updates = mutableListOf<List<UIMessage>>()
        try {
            turn(provider, history, history, updates = updates)
            fail("Provider failure must propagate to entry cleanup")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, updates.size)
        assertEquals("partial", updates.single().last().toText())
    }

    @Test
    fun `failed and cancelled tool turns preserve reported partial usage without a second callback`() = runBlocking {
        for (failure in listOf(IllegalStateException("API failed"), CancellationException("stop"))) {
            val history = listOf(UIMessage.assistant("tool result").copy(usage = TokenUsage(10, 3, 2, 13)))
            val provider = RecordingProvider(listOf(
                chunk(UIMessagePart.Text("partial")).copy(usage = TokenUsage(20, 1, 4, 21)),
            ), failure)
            val updates = mutableListOf<List<UIMessage>>()
            try {
                turn(provider, history, history, updates = updates)
                fail("Failure must propagate")
            } catch (actual: Exception) {
                assertSame(failure, actual)
            }
            assertEquals(TokenUsage(30, 4, 6, 34), updates.single().last().usage)
            assertEquals(history.last().id, updates.single().last().id)
        }
    }

    @Test
    fun `cancel during update prevents consuming later chunks`() = runBlocking {
        val cancellation = CancellationException("stop")
        val provider = RecordingProvider(listOf(
            chunk(UIMessagePart.Text("first")), chunk(UIMessagePart.Text("second")),
        ))
        val history = listOf(UIMessage.user("question"))
        var updates = 0
        try {
            generateModelTurn(provider, setting, history, history, params, model, true, true) {
                updates++
                throw cancellation
            }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(1, updates)
        assertEquals(1, provider.emittedChunks)
    }

    @Test
    fun `local diagnostics never reach streaming or nonstreaming providers or change history text`() = runBlocking {
        val metadata = buildJsonObject { put("memory_recall_v1", "local-only-secret") }
        val history = listOf(
            UIMessage.user("question"), UIMessage.assistant("old reply").copy(
                localMetadata = metadata, generationTimeMs = 123,
            ),
        )
        for (stream in listOf(true, false)) {
            val provider = RecordingProvider(listOf(chunk(UIMessagePart.Text("answer"))))
            turn(provider, history, history, stream = stream)
            val sent = requireNotNull(provider.requestMessages)
            assertTrue(sent.all { it.localMetadata == null })
            assertTrue(sent.all { it.generationTimeMs == null })
            assertEquals(history.map { it.parts }, sent.map { it.parts })
            assertEquals(history.map { it.id }, sent.map { it.id })
            assertEquals(metadata, history.last().localMetadata)
        }
    }

    private suspend fun turn(
        provider: RecordingProvider,
        history: List<UIMessage>,
        local: List<UIMessage>,
        stream: Boolean = true,
        mergeUsage: Boolean = true,
        updates: MutableList<List<UIMessage>> = mutableListOf(),
    ) = generateModelTurn(provider, setting, history, local, params, model, stream, mergeUsage, nanoTime = { 0L }) {
        updates.add(it)
    }

    private fun chunk(part: UIMessagePart) = MessageChunk(
        id = "chunk", model = "model",
        choices = listOf(UIMessageChoice(
            index = 0,
            delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)),
            message = null, finishReason = null,
        )),
    )

    private class RecordingProvider(
        private val chunks: List<MessageChunk>,
        private val failure: Exception? = null,
        private val beforeEmit: (Int) -> Unit = {},
        private val onComplete: () -> Unit = {},
    ) : Provider<ProviderSetting> {
        var streamCalls = 0
        var generateCalls = 0
        var emittedChunks = 0
        var requestMessages: List<UIMessage>? = null
        var setting: ProviderSetting? = null
        var params: TextGenerationParams? = null

        override suspend fun streamText(
            providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams,
        ): Flow<MessageChunk> {
            streamCalls++
            record(providerSetting, messages, params)
            return flow {
                for ((index, chunk) in chunks.withIndex()) {
                    beforeEmit(index)
                    emittedChunks++
                    emit(chunk)
                }
                failure?.let { throw it }
                onComplete()
            }
        }

        override suspend fun generateText(
            providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams,
        ): MessageChunk {
            generateCalls++
            record(providerSetting, messages, params)
            failure?.let { throw it }
            onComplete()
            return chunks.single()
        }

        private fun record(setting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams) {
            this.setting = setting
            requestMessages = messages
            this.params = params
        }

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = error("unused")
        override suspend fun generateImage(
            providerSetting: ProviderSetting, params: ImageGenerationParams,
        ): ImageGenerationResult = error("unused")
    }
}
