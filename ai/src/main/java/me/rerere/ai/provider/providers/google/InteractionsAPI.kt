package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.ui.*
import me.rerere.ai.util.*
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException

/** Adapted from RikkaHub 43f1a5df; keep the existing MessageChunk/agent lifecycle. */
internal class InteractionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default(),
) {
    suspend fun generateText(
        provider: ProviderSetting.Google, messages: List<UIMessage>, params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val request = request(provider, params, buildRequestBody(messages, params, false))
        client.newCall(request).await().use { response ->
            check(response.isSuccessful) { "Gemini Interactions HTTP ${response.code}" }
            val body = json.parseToJsonElement(response.body.string()) as? JsonObject
                ?: error("Invalid Gemini interaction response")
            InteractionState(params.model.modelId).complete(body)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun streamText(
        provider: ProviderSetting.Google, messages: List<UIMessage>, params: TextGenerationParams,
    ): Flow<MessageChunk> = flow {
        val decoder = InteractionState(params.model.modelId)
        events(request(provider, params, buildRequestBody(messages, params, true))).transformWhile { (type, data) ->
            decoder.accept(type, data)?.let { emit(it) }
            !decoder.completed
        }.collect { emit(it) }
        check(decoder.completed) { "Gemini Interactions stream ended before interaction.completed" }
    }.flowOn(Dispatchers.IO)

    // Buffer wire events, not increasingly large message snapshots. Collection cancellation closes the HTTP call.
    private fun events(request: Request): Flow<Pair<String?, String>> = callbackFlow {
        val source = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
                if (trySend(type to data).isFailure) close(IOException("Interaction event delivery failed"))
            }
            override fun onFailure(source: EventSource, error: Throwable?, response: Response?) {
                response?.close()
                close(error ?: IOException("Gemini Interactions HTTP ${response?.code ?: "stream failure"}"))
            }
            override fun onClosed(source: EventSource) { close() }
        })
        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)

    private fun request(provider: ProviderSetting.Google, params: TextGenerationParams, body: JsonObject): Request {
        require(!provider.vertexAI) { "Interactions mode is for the Gemini API; disable it for Vertex AI" }
        val key = keyRoulette.next(provider.apiKey, provider.id.toString())
        require(key.isNotBlank()) { "Gemini API key is required" }
        return Request.Builder().url("${provider.baseUrl.trimEnd('/')}/interactions")
            .headers(params.customHeaders.toHeaders())
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(provider.baseUrl).build()
    }

    internal fun buildRequestBody(messages: List<UIMessage>, params: TextGenerationParams, stream: Boolean): JsonObject {
        val body = buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            val system = messages.filter { it.role == MessageRole.SYSTEM }.flatMap { it.parts }
                .filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            if (system.isNotBlank()) put("system_instruction", system)
            put("input", buildInput(messages))
            val config = buildJsonObject {
                params.temperature?.let { put("temperature", it) }
                params.topP?.let { put("top_p", it) }
                params.maxTokens?.let { put("max_output_tokens", it) }
                if (ModelAbility.REASONING in params.model.abilities) {
                    put("thinking_summaries", "auto")
                    when (params.reasoningLevel) {
                        ReasoningLevel.AUTO -> Unit
                        ReasoningLevel.OFF -> put("thinking_level", if (params.model.modelId.startsWith("gemini-3")) "minimal" else "low")
                        ReasoningLevel.LOW -> put("thinking_level", "low")
                        ReasoningLevel.MEDIUM -> put("thinking_level", "medium")
                        else -> put("thinking_level", "high")
                    }
                }
            }
            if (config.isNotEmpty()) put("generation_config", config)
            if (Modality.IMAGE in params.model.outputModalities) putJsonArray("response_format") {
                add(buildJsonObject { put("type", "text") }); add(buildJsonObject { put("type", "image") })
            }
            val functions = params.tools.takeIf { ModelAbility.TOOL in params.model.abilities }.orEmpty()
            val builtIns = params.model.tools.mapNotNull { when (it) {
                BuiltInTools.Search -> "google_search"
                BuiltInTools.UrlContext -> "url_context"
                else -> null
            } }
            if (functions.isNotEmpty() || builtIns.isNotEmpty()) putJsonArray("tools") {
                functions.forEach { tool -> add(buildJsonObject {
                    put("type", "function"); put("name", tool.name); put("description", tool.description)
                    put("parameters", tool.parameters()?.let { json.encodeToJsonElement(it) } ?: buildJsonObject {
                        put("type", "object"); put("properties", JsonObject(emptyMap()))
                    })
                }) }
                builtIns.forEach { type -> add(buildJsonObject { put("type", type) }) }
            }
        }.mergeCustomBody(params.customBody)
        // Branches, edits and compaction stay on the device; never chain a different server-side history.
        return JsonObject((body - "previous_interaction_id" - "background") + mapOf(
            "store" to JsonPrimitive(false), "stream" to JsonPrimitive(stream),
        ))
    }

    internal fun buildInput(messages: List<UIMessage>): JsonArray = buildJsonArray {
        messages.filter { it.role != MessageRole.SYSTEM }.forEach { message ->
            if (message.role != MessageRole.ASSISTANT) {
                val content = message.parts.mapNotNull(::content)
                if (content.isNotEmpty()) add(step("user_input", content))
            } else {
                val buffer = mutableListOf<JsonObject>()
                fun flush() { if (buffer.isNotEmpty()) { add(step("model_output", buffer.toList())); buffer.clear() } }
                groupPartsByToolBoundary(message.parts).forEach { group -> when (group) {
                    is PartGroup.Content -> group.parts.forEach { part ->
                        if (part is UIMessagePart.Reasoning) {
                            signature(part.metadata)?.let { sig ->
                                flush()
                                add(buildJsonObject {
                                    put("type", "thought"); put("signature", sig)
                                    if (part.reasoning.isNotBlank()) putJsonArray("summary") {
                                        add(buildJsonObject { put("type", "text"); put("text", part.reasoning) })
                                    }
                                })
                            }
                        } else content(part)?.let(buffer::add)
                    }
                    is PartGroup.Tools -> {
                        flush()
                        val functions = group.tools.filter {
                            it.isExecuted && (it.metadata?.get(INTERACTIONS_META) as? JsonObject)?.get("call") == null
                        }
                        group.tools.filter { it !in functions }.forEach { tool ->
                            val meta = tool.metadata?.get(INTERACTIONS_META) as? JsonObject
                            val call = meta?.get("call") as? JsonObject
                            val result = meta?.get("result") as? JsonObject
                            if (call != null && result != null) { add(call); add(result) }
                        }
                        functions.forEach { tool -> add(buildJsonObject {
                            put("type", "function_call"); put("id", tool.toolCallId); put("name", tool.toolName)
                            put("arguments", tool.inputAsJson())
                            signature(tool.metadata)?.let { put("signature", it) }
                        }) }
                        functions.forEach { tool -> add(buildJsonObject {
                            put("type", "function_result"); put("call_id", tool.toolCallId); put("name", tool.toolName)
                            val outputs = tool.output.mapNotNull(::content)
                            put("result", JsonArray(outputs.ifEmpty { listOf(buildJsonObject {
                                put("type", "text"); put("text", " ")
                            }) }))
                        }) }
                    }
                } }
                flush()
            }
        }
    }

    private fun step(type: String, content: List<JsonObject>) = buildJsonObject {
        put("type", type); put("content", JsonArray(content))
    }

    private fun content(part: UIMessagePart): JsonObject? = when (part) {
        is UIMessagePart.Text -> part.text.takeIf { it.isNotEmpty() }?.let { buildJsonObject {
            put("type", "text"); put("text", it)
        } }
        is UIMessagePart.Image -> part.encodeBase64(false).getOrThrow().let { encoded -> buildJsonObject {
            put("type", "image")
            if (encoded.base64.startsWith("http")) put("uri", encoded.base64)
            else { put("mime_type", encoded.mimeType); put("data", encoded.base64.substringAfter(";base64,")) }
        } }
        is UIMessagePart.Audio -> audio(part)
        is UIMessagePart.VoiceMessage -> audio(UIMessagePart.Audio(part.url))
        is UIMessagePart.Video -> buildJsonObject {
            put("type", "video"); put("mime_type", "video/mp4"); put("data", part.encodeBase64(false).getOrThrow())
        }
        is UIMessagePart.Document -> error("Convert document attachments to text before using Interactions")
        else -> null
    }

    private fun audio(part: UIMessagePart.Audio): JsonObject = buildJsonObject {
        put("type", "audio")
        if (part.url.startsWith("https://") || part.url.startsWith("http://")) put("uri", part.url)
        else part.encodeAudio().let { put("mime_type", it.mimeType); put("data", it.data) }
    }
}
