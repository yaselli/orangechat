package me.rerere.ai.provider.providers.grok

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

private const val XAI_BASE_URL = "https://api.x.ai/v1"
private const val GROK_CLI_BASE_URL = "https://cli-chat-proxy.grok.com/v1"
private val NON_CHAT_MODEL = Regex("imagine|tts|embed|video", RegexOption.IGNORE_CASE)
private const val WEEK_MINUTES = 7 * 24 * 60
internal val GROK_CAPABILITIES = ResponseProviderCapabilities(
    supportsReasoningSummary = false, systemMessageRole = "system",
    alwaysIncludeEncryptedContent = true, reasoningEffort = ::grokEffort,
)

private val MODELS_WITHOUT_REASONING_EFFORT = Regex("""^grok-4\.20(-\d{4})?-(non-)?reasoning$""")

/**
 * 与 Vercel AI SDK 的 xAI provider 一致：关闭发 `none`（grok-4.3 及更新的推理模型支持），
 * `xhigh` 只有 grok-4.6 支持，其他模型落到 `high`。
 */
private fun grokEffort(level: ReasoningLevel, modelId: String): String? {
    if (MODELS_WITHOUT_REASONING_EFFORT.matches(modelId)) return null
    return when (level) {
        ReasoningLevel.AUTO -> null
        ReasoningLevel.OFF -> "none"
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH -> if (modelId == "grok-4.6") "xhigh" else "high"
    }
}

class GrokProvider(client: OkHttpClient, private val credentials: AccountCredentialProvider) :
    Provider<ProviderSetting.Grok> {
    private val client = client.withAccountAuthenticator(credentials)

    override suspend fun listModels(providerSetting: ProviderSetting.Grok): List<Model> = coroutineScope {
        suspend fun catalog(url: String, cli: Boolean = false): Result<JsonObject> = try {
            Result.success(getJson(providerSetting, url, cli))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        val cli = async { catalog("$GROK_CLI_BASE_URL/models-v2", true) }
        val api = async { catalog("$XAI_BASE_URL/models") }
        val cliResult = cli.await()
        val apiResult = api.await()
        if (cliResult.isFailure && apiResult.isFailure) throw cliResult.exceptionOrNull()!!
        parseGrokModels(cliResult.getOrNull(), apiResult.getOrNull())
    }

    override suspend fun getQuota(providerSetting: ProviderSetting.Grok): ProviderQuota {
        val credits = getJson(providerSetting, "$GROK_CLI_BASE_URL/billing?format=credits", true)
        val weekly = parseGrokWeeklyUsage(credits)
        if (weekly != null && !credits.isUnifiedBilling()) return weekly
        return parseGrokMonthlyUsage(getJson(providerSetting, "$GROK_CLI_BASE_URL/billing", true))
            ?: weekly ?: ProviderQuota.UsageLimits(emptyList(), false)
    }

    override suspend fun streamText(providerSetting: ProviderSetting.Grok,
        messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk> {
        val (api, setting) = accountResponses(client, credentials, providerSetting, XAI_BASE_URL, GROK_CAPABILITIES)
        return api.streamText(setting, messages, params)
    }

    override suspend fun generateText(providerSetting: ProviderSetting.Grok,
        messages: List<UIMessage>, params: TextGenerationParams): MessageChunk {
        val (api, setting) = accountResponses(client, credentials, providerSetting, XAI_BASE_URL, GROK_CAPABILITIES)
        return api.generateText(setting, messages, params)
    }

    override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): ImageGenerationResult =
        error("订阅账号提供商暂不支持图片生成接口")

    private suspend fun getJson(setting: ProviderSetting.Grok, url: String, cli: Boolean = false): JsonObject {
        val request = Request.Builder().url(url).apply {
            configureAccountAuth(credentials, setting, emptyList()) {
                if (cli) header("X-XAI-Token-Auth", "xai-grok-cli")
            }
        }.get().build()
        return json.parseToJsonElement(client.fetchBody(request)).jsonObject
    }
}

/**
 * 合并两个模型目录，两边都有的以 Grok CLI 的为准。
 *
 * 目录里声明了推理档位的模型才算推理模型，只有它们会带上 `reasoning.effort`；
 * 其余模型（包括自带推理但不接受档位的）都按普通模型请求。
 */
internal fun parseGrokModels(cliCatalog: JsonObject?, apiCatalog: JsonObject?): List<Model> {
    val models = linkedMapOf<String, Model>()
    listOfNotNull(cliCatalog, apiCatalog).forEach { catalog ->
        (catalog["data"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { row ->
            val meta = row["_meta"] as? JsonObject
            // Grok CLI 目录里的 id 可能和推理用的模型 id 不同
            val id = row.string("model") ?: row.string("modelId") ?: row.string("id")
                ?: meta?.string("model") ?: meta?.string("modelId") ?: return@forEach
            val hidden = (row["hidden"] ?: meta?.get("hidden"))?.jsonPrimitiveOrNull?.booleanOrNull == true
            if (hidden || NON_CHAT_MODEL.containsMatchIn(id) || id in models) return@forEach
            val efforts = (row["reasoningEfforts"] ?: row["reasoning_efforts"] ?: meta?.get("reasoningEfforts"))
                as? JsonArray
            val contextWindow = listOf(
                row["contextWindow"],
                row["context_window"],
                meta?.get("contextWindow"),
                meta?.get("totalContextTokens")
            ).firstNotNullOfOrNull { it?.number()?.toInt() }
            models[id] = Model(
                modelId = id,
                displayName = row.string("name") ?: row.string("displayName") ?: row.string("display_name")
                    ?: meta?.string("displayName") ?: id,
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                abilities = if (efforts.isNullOrEmpty()) {
                    listOf(ModelAbility.TOOL)
                } else {
                    listOf(ModelAbility.TOOL, ModelAbility.REASONING)
                },
                contextWindow = contextWindow ?: Model().contextWindow,
            )
        }
    }
    return models.values.toList()
}

/** `GET /billing?format=credits`：SuperGrok 的按周额度，不是这种账号时返回 null */
internal fun parseGrokWeeklyUsage(body: JsonObject, now: Instant = Clock.System.now()): ProviderQuota.UsageLimits? {
    val config = body["config"] as? JsonObject ?: return null
    val period = config["currentPeriod"] as? JsonObject ?: return null
    val start = period.instant("start") ?: return null
    val end = period.instant("end") ?: return null
    if (end <= start || period.string("type")?.uppercase()?.contains("WEEK") != true) return null
    // 新一周还没有用量时不返回 creditUsagePercent
    val percent = config["creditUsagePercent"]?.number() ?: if (end > now) 0.0 else return null
    return ProviderQuota.UsageLimits(
        windows = listOf(
            UsageWindow(usedPercent = percent.coerceIn(0.0, 100.0), windowMinutes = WEEK_MINUTES, resetsAt = end)
        ),
        limitReached = percent >= 100,
    )
}

/** `GET /billing`：统一计费账号的按月额度，没有额度时返回 null */
internal fun parseGrokMonthlyUsage(body: JsonObject): ProviderQuota.UsageLimits? {
    val config = body["config"] as? JsonObject ?: return null
    val start = config.instant("billingPeriodStart") ?: return null
    val end = config.instant("billingPeriodEnd") ?: return null
    val limit = (config["monthlyLimit"] as? JsonObject)?.get("val")?.number()
    val used = (config["used"] as? JsonObject)?.get("val")?.number()
    if (end <= start || limit == null || limit <= 0 || used == null) return null
    return ProviderQuota.UsageLimits(
        windows = listOf(
            UsageWindow(
                usedPercent = (used / limit * 100).coerceIn(0.0, 100.0),
                // 自然月长短不一，按接口给的账期算
                windowMinutes = (end - start).inWholeMinutes.toInt(),
                resetsAt = end,
            )
        ),
        limitReached = used >= limit,
    )
}

private fun JsonObject.isUnifiedBilling(): Boolean =
    (get("config") as? JsonObject)?.get("isUnifiedBillingUser")?.jsonPrimitiveOrNull?.booleanOrNull == true

private fun JsonObject.string(key: String): String? = stringOrNull(key)?.takeIf { it.isNotBlank() }

// 数字可能以字符串给出
private fun JsonElement.number(): Double? = jsonPrimitiveOrNull?.contentOrNull?.toDoubleOrNull()

private fun JsonObject.instant(key: String): Instant? = string(
    key
)?.let { runCatching { Instant.parse(it) }.getOrNull() }
