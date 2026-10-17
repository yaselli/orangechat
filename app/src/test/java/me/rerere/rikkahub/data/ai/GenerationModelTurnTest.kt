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
            UIMessage.user("question"), UIMessage.assistant("old reply").copy(localMetadata = metadata),
        )
        for (stream in listOf(true, false)) {
            val provider = RecordingProvider(listOf(chunk(UIMessagePart.Text("answer"))))
            turn(provider, history, history, stream = stream)
            val sent = requireNotNull(provider.requestMessages)
            assertTrue(sent.all { it.localMetadata == null })
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
    ) = generateModelTurn(provider, setting, history, local, params, model, stream, mergeUsage) {
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
                for (chunk in chunks) {
                    emittedChunks++
                    emit(chunk)
                }
                failure?.let { throw it }
            }
        }

        override suspend fun generateText(
            providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams,
        ): MessageChunk {
            generateCalls++
            record(providerSetting, messages, params)
            failure?.let { throw it }
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
