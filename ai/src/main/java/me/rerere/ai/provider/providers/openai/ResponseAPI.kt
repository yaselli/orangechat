/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

private const val TAG = "ResponseAPI"

class ResponseAPI private constructor(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette,
    private val capabilityOverride: ResponseProviderCapabilities?,
) : OpenAIImpl {
    constructor(client: OkHttpClient, keyRoulette: KeyRoulette = KeyRoulette.default()) : this(client, keyRoulette, null)
    internal constructor(client: OkHttpClient, capabilities: ResponseProviderCapabilities) :
        this(client, KeyRoulette.default(), capabilities)
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk {
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = false,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}"
            )
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.d(TAG, "request prepared; payload omitted")

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        Log.d(TAG, "provider event; payload omitted")
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val output = parseResponseOutput(bodyJson)

        return output
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}"
            )
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.d(TAG, "request prepared; payload omitted")

        // Per-request state: simultaneous conversations must not share item/call IDs.
        val toolState = ResponseToolStreamState()
        var completed = false
        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    if (capabilityOverride != null && !completed) close(IllegalStateException("Account stream ended before completion"))
                    else close()
                    return
                }
                Log.d(TAG, "stream event received; payload omitted")
                val chunkJson = try {
                    parseOpenAIStreamObject(data) ?: return
                } catch (e: Throwable) {
                    // 上游真的发了坏数据时不要让整个流直接崩掉裸抛 Unexpected EOF。
                    // Only record size: parser messages and previews can expose reply/tool content.
                    Log.w(TAG, "onEvent: failed to parse SSE data (len=${data.length})")
                    close(
                        Exception("Failed to parse stream data: ${e.message} (data length=${data.length})", e)
                    )
                    return
                }
                val eventType = chunkJson["type"]?.jsonPrimitiveOrNull?.contentOrNull ?: type
                if (capabilityOverride != null && eventType in setOf("response.failed", "response.incomplete", "error")) {
                    close(IllegalStateException("Account generation did not complete"))
                    return
                }
                if (eventType == "response.completed") completed = true
                val chunk = try {
                    parseResponseDelta(chunkJson, toolState)
                } catch (e: Exception) {
                    close(e)
                    return
                }
                if (chunk != null) {
                    trySend(chunk)
                }
                if (type == "response.completed" || capabilityOverride != null && completed) {
                    close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                Log.w(TAG, "Exception details omitted from diagnostics")
                Log.d(TAG, "provider event; payload omitted")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        Log.d(TAG, "Provider payload omitted")
                        exception = bodyElement.parseErrorDetail()
                        Log.d(TAG, "provider event; payload omitted")
                    }
                } catch (e: Throwable) {
                    Log.d(TAG, "provider event; payload omitted")
                    Log.w(TAG, "Exception details omitted from diagnostics")
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (capabilityOverride != null && !completed) close(IllegalStateException("Account stream ended before completion"))
                else close()
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta, 导致回复中间缺字 (#1295), 因此缓冲必须无界。
        // 与上游 rikkahub 对齐: 在 callbackFlow 上叠加 Channel.UNLIMITED 缓冲。
    }.buffer(Channel.UNLIMITED)

    internal fun buildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val capabilities = capabilityOverride ?: resolveResponseProviderCapabilities(host)
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            if (!params.model.tools.contains(BuiltInTools.ImageGeneration)) {
                put("store", false)
            }

            if (capabilities.supportsSamplingParams && isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (capabilities.supportsSamplingParams && params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            // Legacy API behavior stays unchanged; account endpoints put all system prompts into input.
            if (capabilities.systemMessageRole == null && messages.any { it.role == MessageRole.SYSTEM }) {
                val parts = messages.first { it.role == MessageRole.SYSTEM }.parts
                put("instructions", parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
            }
            val history = buildMessages(messages)
            put("input", if (capabilities.systemMessageRole == null) history else buildJsonArray {
                messages.filter { it.role == MessageRole.SYSTEM }.forEach { message ->
                    add(buildJsonObject {
                        put("role", capabilities.systemMessageRole)
                        put("content", message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
                    })
                }
                history.forEach { add(it) }
            })

            // Account endpoints always request replayable encrypted state, even for models without adjustable reasoning.
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                put("reasoning", buildJsonObject {
                    if (capabilities.supportsReasoningSummary) put("summary", "auto")
                    val effort = capabilities.reasoningEffort?.invoke(level, params.model.modelId)
                        ?: if (capabilities.reasoningEffort == null && level != ReasoningLevel.AUTO) level.effort else null
                    if (effort != null) put("effort", effort)
                })
            }
            if (capabilities.supportEncryptedContent &&
                (params.model.abilities.contains(ModelAbility.REASONING) || capabilities.alwaysIncludeEncryptedContent)) {
                put("include", buildJsonArray { add("reasoning.encrypted_content") })
            }

            // tools
            val functionTools = if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                buildJsonArray {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            put(
                                "parameters",
                                tool.parameters()?.let { json.encodeToJsonElement(it) }
                                    ?: if (capabilityOverride != null) buildJsonObject {
                                        put("type", "object"); put("properties", JsonObject(emptyMap()))
                                    } else JsonNull
                            )
                        })
                    }
                }
            } else null
            if (functionTools != null) put("tools", functionTools)
            // built-in tools
            if (params.model.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    if (capabilityOverride != null) functionTools?.forEach { add(it) }
                    params.model.tools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("type", "web_search")
                                })
                            }

                            BuiltInTools.UrlContext -> {} // not supported

                            BuiltInTools.ImageGeneration -> {
                                add(buildJsonObject {
                                    put("type", "image_generation")
                                    put("model", "gpt-image-2")
                                })
                            }
                        }
                    }
                }
            }
        }.mergeCustomBody(params.customBody).let { body ->
            if (capabilityOverride == null) body else JsonObject(body.toMutableMap().apply {
                put("store", JsonPrimitive(false))
                remove("previous_response_id")
                if (!capabilities.supportsSamplingParams) {
                    remove("temperature"); remove("top_p"); remove("max_output_tokens")
                }
                if (get("reasoning") is JsonObject && (get("reasoning") as JsonObject).isEmpty()) remove("reasoning")
                if (capabilities.forceStreaming) put("stream", JsonPrimitive(true))
            })
        }
    }

    internal fun buildMessages(messages: List<UIMessage>) = buildJsonArray {
        require(messages.none { message -> message.parts.any { it is UIMessagePart.Audio } }) {
            "原始音频请使用 Chat Completions 接口；请关闭该提供商的 Responses API 选项"
        }
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantItems(message)
                } else {
                    addUserItems(message)
                }
            }
    }

    private fun JsonArrayBuilder.addAssistantItems(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Reasoning -> {
                                // 先输出累积的文本/图片内容
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                // 输出 reasoning item
                                add(buildJsonObject {
                                    put("type", "reasoning")
                                    part.metadata?.get("reasoning_id")?.jsonPrimitiveOrNull?.contentOrNull?.let {
                                        put("id", it)
                                    }
                                    put("summary", buildJsonArray {
                                        add(buildJsonObject {
                                            put("type", "summary_text")
                                            put("text", part.reasoning)
                                        })
                                    })
                                    part.metadata?.get("encrypted_content")?.jsonPrimitiveOrNull?.contentOrNull?.let {
                                        put(
                                            "encrypted_content",
                                            part.metadata?.get("encrypted_content")?.jsonPrimitive?.contentOrNull ?: ""
                                        )
                                    }
                                })
                            }

                            is UIMessagePart.Image -> {
                                val callId = part.metadata?.get("openai_image_call_id")?.jsonPrimitive?.contentOrNull
                                if (callId != null) {
                                    if (contentBuffer.isNotEmpty()) {
                                        addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                        contentBuffer.clear()
                                    }
                                    add(buildJsonObject {
                                        put("type", "image_generation_call")
                                        put("id", callId)
                                    })
                                } else {
                                    contentBuffer.add(part)
                                }
                            }

                            is UIMessagePart.Text -> {
                                contentBuffer.add(part)
                            }

                            else -> {}
                        }
                    }
                }

                is PartGroup.Tools -> {
                    // 先输出累积的内容
                    if (contentBuffer.isNotEmpty()) {
                        addContentItem(MessageRole.ASSISTANT, contentBuffer)
                        contentBuffer.clear()
                    }

                    // 输出 function_call + function_call_output
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tool.toolCallId)
                            put("name", tool.toolName)
                            put("arguments", tool.input)
                        })
                        val textOutput = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                        val imageOutput = tool.output.filterIsInstance<UIMessagePart.Image>()

                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", tool.toolCallId)
                            put("output", textOutput)
                        })

                        // If tool output contains images, inject a user message with the images
                        if (imageOutput.isNotEmpty()) {
                            add(buildJsonObject {
                                put("role", "user")
                                putJsonArray("content") {
                                    add(buildJsonObject {
                                        put("type", "input_text")
                                        put("text", "[Tool ${tool.toolName} returned an image]")
                                    })
                                    imageOutput.forEach { imagePart ->
                                        add(buildJsonObject {
                                            imagePart.encodeBase64().onSuccess { encodedImage ->
                                                put("type", "input_image")
                                                put("image_url", encodedImage.base64)
                                            }.onFailure {
                                                put("type", "input_text")
                                                put("text", "[Image encoding failed: ${it.message}]")
                                            }
                                        })
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            addContentItem(MessageRole.ASSISTANT, contentBuffer)
        }
    }

    private fun JsonArrayBuilder.addUserItems(message: UIMessage) {
        val contentParts = message.parts.filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
        if (contentParts.isNotEmpty()) {
            addContentItem(message.role, contentParts)
        }
    }

    private fun JsonArrayBuilder.addContentItem(role: MessageRole, parts: List<UIMessagePart>) {
        if (parts.isEmpty()) return

        add(buildJsonObject {
            put("role", JsonPrimitive(role.name.lowercase()))

            if (parts.isOnlyTextPart()) {
                put("content", (parts.first() as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", if (role == MessageRole.USER) "input_image" else "output_image")
                                        put("image_url", encodedImage.base64)
                                    }.onFailure {
                                        Log.w(TAG, "Exception details omitted from diagnostics")
                                        put("type", "input_text")
                                        put("text", "Error: Failed to encode image to base64")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    internal fun parseResponseDelta(
        jsonObject: JsonObject,
        toolState: ResponseToolStreamState,
    ): MessageChunk? {
        val chunkType = jsonObject["type"]?.jsonPrimitive?.content ?: error("chunk type not found")

        when (chunkType) {
            "response.output_text.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage.assistant(
                                jsonObject["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Reasoning(
                                        reasoning = jsonObject["delta"]?.jsonPrimitive?.contentOrNull
                                            ?: "",
                                        createdAt = Clock.System.now(),
                                        finishedAt = null
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.output_item.added" -> {
                val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                if (type == "function_call_output") {
                    toolState.rememberOutput(item)
                    return null // The added item may still have a provisional/empty output.
                }
                val id = item["id"]?.jsonPrimitive?.contentOrNull
                    ?: item["call_id"]?.jsonPrimitive?.contentOrNull ?: error("chunk id not found")
                if (type == "function_call") {
                    val tool = toolState.functionCall(item, jsonObject["output_index"]?.jsonPrimitive?.intOrNull)
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        tool
                                    )
                                ),
                                finishReason = null
                            )
                        )
                    )
                } else if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.content
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Reasoning(
                                            reasoning = "",
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = buildJsonObject {
                                                put("encrypted_content", encryptedContent)
                                                put("reasoning_id", id)
                                            }
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    val callId = item["id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    return MessageChunk(
                        id = callId,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Image(
                                            url = "",
                                            metadata = buildJsonObject {
                                                put("openai_image_call_id", callId)
                                            }
                                        )
                                    )
                                ),
                                message = null,
                                finishReason = null
                            )
                        )
                    )
                }
            }

            "response.output_item.done" -> {
                val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                if (type == "function_call_output") {
                    toolState.rememberOutput(item)
                    return toolState.outputChunk(item["call_id"]?.jsonPrimitive?.contentOrNull)
                }
                if (type == "function_call") {
                    return toolState.chunk(listOf(
                        toolState.functionCall(item, jsonObject["output_index"]?.jsonPrimitive?.intOrNull)
                    ))
                }
                val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
                if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.content
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Reasoning(
                                            reasoning = "",
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = buildJsonObject {
                                                put("encrypted_content", encryptedContent)
                                                put("reasoning_id", id)
                                            }
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    val result = item["result"]?.jsonPrimitive?.content ?: error("result not found")
                    return MessageChunk(
                        id = item["id"]?.jsonPrimitive?.content ?: error("item_id not found"),
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Image(
                                            url = result,
                                            metadata = buildJsonObject {
                                                put("openai_image_call_id", item["id"]?.jsonPrimitive?.content ?: "")
                                            }
                                        )
                                    )
                                ),
                                message = null,
                                finishReason = null
                            )
                        )
                    )
                }
            }

            "response.function_call_arguments.done" -> {
                val itemId =
                    jsonObject["item_id"]?.jsonPrimitive?.content ?: error("item_id not found")
                val toolCallId = toolState.callId(itemId, jsonObject["output_index"]?.jsonPrimitive?.intOrNull)
                val arguments =
                    jsonObject["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                return MessageChunk(
                    id = toolCallId,
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Tool(
                                        toolCallId = toolCallId,
                                        toolName = "",
                                        input = toolState.argumentSuffix(toolCallId, arguments),
                                        output = emptyList()
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    ),
                )
            }

            "response.completed" -> {
                val response = jsonObject["response"]?.jsonObjectOrNull
                (response?.get("output") as? JsonArray)?.forEach { element ->
                    val item = element.jsonObjectOrNull ?: return@forEach
                    if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call_output") {
                        toolState.rememberOutput(item)
                    }
                }
                val outputs = toolState.remainingOutputs()
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = if (outputs.isEmpty()) emptyList() else listOf(UIMessageChoice(
                        index = 0,
                        delta = UIMessage(role = MessageRole.ASSISTANT, parts = outputs),
                        message = null,
                        finishReason = null,
                    )),
                    usage = parseTokenUsage(response?.get("usage")?.jsonObjectOrNull)
                )
            }
        }

        return null
    }

    internal fun parseResponseOutput(jsonObject: JsonObject): MessageChunk {
        val outputs = jsonObject["output"]?.jsonArray ?: error("output not found")
        val parts = arrayListOf<UIMessagePart>()

        outputs.forEach { outputItem ->
            val output = outputItem.jsonObject
            val type = output["type"]?.jsonPrimitive?.content ?: error("output type not found")
            when (type) {
                "reasoning" -> {
                    val summary = output["summary"]?.jsonArray ?: error("summary not found")
                    summary.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "summary_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Reasoning(
                                        reasoning = text,
                                        createdAt = Clock.System.now(),
                                        finishedAt = Clock.System.now()
                                    )
                                )
                            }
                        }
                    }
                }

                "function_call" -> {
                    val callId = output["call_id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    val name = output["name"]?.jsonPrimitive?.content ?: error("name not found")
                    val arguments =
                        output["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = callId,
                            toolName = name,
                            input = arguments,
                            output = emptyList()
                        )
                    )
                }

                "message" -> {
                    val content = output["content"]?.jsonArray ?: error("content not found")
                    content.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "output_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Text(
                                        text = text
                                    )
                                )
                            }

                            else -> error("unknown part type $partType")
                        }
                    }
                }
            }
        }

        // Resolve by call_id after collecting calls, including gateways that return outputs first.
        outputs.forEach { outputItem ->
            val output = outputItem.jsonObjectOrNull ?: return@forEach
            if (output["type"]?.jsonPrimitive?.contentOrNull != "function_call_output") return@forEach
            val callId = output["call_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val index = parts.indexOfLast { it is UIMessagePart.Tool && it.toolCallId == callId }
            if (index >= 0) {
                val tool = parts[index] as UIMessagePart.Tool
                parts[index] = tool.copy(output = listOf(UIMessagePart.Text(output.functionCallOutputText())))
            }
        }

        return MessageChunk(
            id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "",
            model = jsonObject["model"]?.jsonPrimitive?.contentOrNull ?: "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = parts,
                    ),
                    finishReason = null,
                    delta = null
                )
            ),
            usage = parseTokenUsage(jsonObject["usage"]?.jsonObjectOrNull)
        )
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["input_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }
}

private fun isModelAllowTemperature(model: Model): Boolean {
    return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
}

private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
    val texts = filter { it is UIMessagePart.Text }.size
    return gonnaSend == texts && texts == 1
}

internal data class ResponseProviderCapabilities(
    val supportsReasoningSummary: Boolean = true,
    val supportEncryptedContent: Boolean = true,
    val supportsSamplingParams: Boolean = true,
    val systemMessageRole: String? = null,
    val alwaysIncludeEncryptedContent: Boolean = false,
    val reasoningEffort: ((ReasoningLevel, String) -> String?)? = null,
    val forceStreaming: Boolean = false,
)

internal fun resolveResponseProviderCapabilities(host: String): ResponseProviderCapabilities {
    return when (host) {
        "ark.cn-beijing.volces.com" -> ResponseProviderCapabilities(
            supportsReasoningSummary = false,
            supportEncryptedContent = false
        )

        else -> ResponseProviderCapabilities()
    }
}

