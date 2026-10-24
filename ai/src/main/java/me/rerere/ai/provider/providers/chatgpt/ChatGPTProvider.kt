package me.rerere.ai.provider.providers.chatgpt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.configureAccountAuth
import me.rerere.ai.provider.providers.withAccountAuthenticator
import me.rerere.ai.provider.providers.openai.ResponseProviderCapabilities
import me.rerere.ai.provider.providers.accountResponses
import me.rerere.ai.ui.*
import me.rerere.ai.util.fetchBody
import me.rerere.ai.util.json
import me.rerere.ai.util.stringOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Clock
import kotlin.time.Instant

internal const val CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex"
private const val CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
private const val CODEX_CLIENT_VERSION = "0.163.0"
internal val CODEX_CAPABILITIES = ResponseProviderCapabilities(
    systemMessageRole = "developer", supportsSamplingParams = false,
    alwaysIncludeEncryptedContent = true, forceStreaming = true,
)

class ChatGPTProvider(client: OkHttpClient, private val credentials: AccountCredentialProvider) :
    Provider<ProviderSetting.ChatGPT> {
    private val client = client.withAccountAuthenticator(credentials)

    override suspend fun listModels(providerSetting: ProviderSetting.ChatGPT): List<Model> =
        parseCodexModels(get(providerSetting, "$CODEX_BASE_URL/models?client_version=$CODEX_CLIENT_VERSION"))

    override suspend fun getQuota(providerSetting: ProviderSetting.ChatGPT): ProviderQuota =
        parseCodexUsage(get(providerSetting, CODEX_USAGE_URL))

    override suspend fun streamText(providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk> {
        val (api, setting) = accountResponses(client, credentials, providerSetting, CODEX_BASE_URL, CODEX_CAPABILITIES)
        return api.streamText(setting, messages, params)
    }

    override suspend fun generateText(providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>, params: TextGenerationParams): MessageChunk {
        var output = UIMessage.assistant("").copy(parts = emptyList(), modelId = params.model.id)
        var last: MessageChunk? = null
        streamText(providerSetting, messages, params).collect { chunk -> output += chunk; last = chunk }
        val final = last ?: error("No response received")
        return final.copy(choices = listOf(UIMessageChoice(0, null, output,
            final.choices.firstOrNull()?.finishReason ?: "stop")), usage = final.usage)
    }

    override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): ImageGenerationResult =
        error("订阅账号提供商暂不支持图片生成接口")

    private suspend fun get(setting: ProviderSetting.ChatGPT, url: String): String {
        val request = Request.Builder().url(url).apply {
            configureAccountAuth(credentials, setting, emptyList()) { credential ->
                credential.accountId?.let { header("ChatGPT-Account-ID", it) }
                header("originator", "codex_cli_rs")
            }
        }.get().build()
        return client.fetchBody(request)
    }
}

/** 解析 `GET /models` 的结果，只保留 Codex 选择器里可见的模型 */
internal fun parseCodexModels(body: String): List<Model> {
    val models = json.parseToJsonElement(body).jsonObject["models"]?.jsonArray.orEmpty()
        .map { it.jsonObject }
    val visible = models.filter { it.stringOrNull("visibility") == "list" }.ifEmpty { models }
    return visible.mapNotNull { model ->
        val slug = model.stringOrNull("slug") ?: return@mapNotNull null
        val inputModalities = model["input_modalities"]?.jsonArray
            ?.mapNotNull { modality ->
                when (modality.jsonPrimitiveOrNull?.contentOrNull) {
                    "text" -> Modality.TEXT
                    "image" -> Modality.IMAGE
                    else -> null
                }
            }
            ?.ifEmpty { null }
            ?: listOf(Modality.TEXT, Modality.IMAGE)
        val reasoning = model["supported_reasoning_levels"]?.jsonArray?.isNotEmpty() == true
        Model(
            modelId = slug,
            displayName = model.stringOrNull("display_name") ?: slug,
            inputModalities = inputModalities,
            abilities = if (reasoning) listOf(ModelAbility.TOOL, ModelAbility.REASONING) else listOf(ModelAbility.TOOL),
            contextWindow = model["context_window"]?.jsonPrimitiveOrNull?.intOrNull ?: Model().contextWindow,
        )
    }
}

/** 解析 `GET /wham/usage`，取 `rate_limit` 下的两个窗口（通常是 5 小时和一周） */
internal fun parseCodexUsage(body: String): ProviderQuota.UsageLimits {
    val rateLimit = json.parseToJsonElement(body).jsonObject["rate_limit"] as? JsonObject
    val windows = listOf("primary_window", "secondary_window").mapNotNull { key ->
        val window = rateLimit?.get(key) as? JsonObject ?: return@mapNotNull null
        val usedPercent = window["used_percent"]?.jsonPrimitiveOrNull?.doubleOrNull ?: return@mapNotNull null
        val seconds = window["limit_window_seconds"]?.jsonPrimitiveOrNull?.longOrNull
        val resetAt = window["reset_at"]?.jsonPrimitiveOrNull?.longOrNull
        UsageWindow(
            usedPercent = usedPercent,
            windowMinutes = seconds?.takeIf { it > 0 }?.let { ((it + 59) / 60).toInt() },
            resetsAt = resetAt?.takeIf { it > 0 }?.let { Instant.fromEpochSeconds(it) },
        )
    }
    return ProviderQuota.UsageLimits(
        windows = windows,
        limitReached = rateLimit?.get("limit_reached")?.jsonPrimitiveOrNull?.booleanOrNull == true,
    )
}
