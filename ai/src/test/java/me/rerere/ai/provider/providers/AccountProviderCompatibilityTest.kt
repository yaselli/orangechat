package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.chatgpt.*
import me.rerere.ai.provider.providers.grok.*
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AccountProviderCompatibilityTest {
    @get:org.junit.Rule val timeout = org.junit.rules.Timeout.seconds(20)
    private val model = Model(modelId = "test-model", abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING))

    @Test
    fun codexProtectsRequiredOptionsAndKeepsAllSystemPrompts() {
        val body = ResponseAPI(OkHttpClient(), CODEX_CAPABILITIES).buildRequestBody(
            ProviderSetting.OpenAI(baseUrl = CODEX_BASE_URL),
            listOf(UIMessage.system("persona"), UIMessage.system("memory"), UIMessage.user("hello")),
            TextGenerationParams(model.copy(tools = setOf(BuiltInTools.Search)), temperature = 1f, topP = 0.9f, maxTokens = 100,
                tools = listOf(me.rerere.ai.core.Tool("echo", "echo", execute = { emptyList() })),
                customBody = listOf(CustomBody("store", JsonPrimitive(true)), CustomBody("temperature", JsonPrimitive(1)))), false,
        )
        assertEquals(setOf("function", "web_search"), body["tools"]!!.jsonArray.map {
            it.jsonObject["type"]!!.jsonPrimitive.content
        }.toSet())
        assertEquals("object", body["tools"]!!.jsonArray.first().jsonObject["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("instructions" in body)
        assertFalse("temperature" in body)
        assertFalse("max_output_tokens" in body)
        assertEquals(false, body["store"]!!.jsonPrimitive.boolean)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        val input = body["input"]!!.jsonArray
        assertEquals(listOf("persona", "memory"), input.take(2).map { it.jsonObject["content"]!!.jsonPrimitive.content })
        assertEquals("developer", input[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(body["include"]!!.jsonArray.any { it.jsonPrimitive.content == "reasoning.encrypted_content" })
    }

    @Test
    fun grokDoesNotSendUnsupportedSummaryOrEffort() {
        val body = ResponseAPI(OkHttpClient(), GROK_CAPABILITIES).buildRequestBody(
            ProviderSetting.OpenAI(), listOf(UIMessage.system("persona"), UIMessage.user("hello")),
            TextGenerationParams(model.copy(modelId = "grok-4.20-reasoning"), reasoningLevel = ReasoningLevel.HIGH), true,
        )
        assertFalse("reasoning" in body)
        assertEquals("system", body["input"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun codexMissingContentTypeStreamsAndTruncationIsReported() = runBlocking {
        var observed: Request? = null
        var complete = true
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            observed = chain.request()
            val events = """
                data: {"type":"response.output_text.delta","item_id":"msg","output_index":0,"delta":"hello"}

            """.trimIndent() + "\n\n" + if (complete) """
                data: {"type":"response.completed","response":{"id":"resp","model":"test-model","output":[],"usage":{"input_tokens":7,"output_tokens":2,"total_tokens":9}}}

            """.trimIndent() + "\n\n" else ""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(events.toResponseBody(null)).build()
        }.build()
        val credentials = AccountCredentialProvider { _, _ -> OAuthCredential("fresh-access", accountId = "account-id") }
        val provider = ChatGPTProvider(client, credentials)
        val setting = ProviderSetting.ChatGPT()
        val output = provider.generateText(setting, listOf(UIMessage.user("hi")), TextGenerationParams(model))
        assertEquals("hello", output.choices.single().message!!.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
        assertEquals("Bearer fresh-access", observed!!.header("Authorization"))
        assertEquals("account-id", observed!!.header("ChatGPT-Account-ID"))
        assertEquals("codex_cli_rs", observed!!.header("originator"))
        assertEquals("chatgpt.com", observed!!.url.host)
        assertEquals(7, output.usage?.promptTokens)
        complete = false
        assertTrue(runCatching { provider.streamText(setting, listOf(UIMessage.user("hi")), TextGenerationParams(model)).toList() }.isFailure)
    }

    @Test
    fun unauthorizedRequestsRefreshOnlyOnceAndProtectAuthenticationHeaders() = runBlocking {
        var rejected: String? = null
        val credentials = AccountCredentialProvider { _, token ->
            rejected = token
            OAuthCredential(if (token == null) "old" else "new", accountId = "account")
        }
        val setting = ProviderSetting.ChatGPT(customHeaders = listOf(CustomHeader("Authorization", "bad")))
        val request = Request.Builder().url("https://chatgpt.com/backend-api/codex/models").apply {
            configureAccountAuth(credentials, setting, emptyList()) { header("ChatGPT-Account-ID", it.accountId!!) }
        }.build()
        assertEquals("Bearer old", request.header("Authorization"))
        val client = OkHttpClient().withAccountAuthenticator(credentials)
        val unauthorized = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(401).message("No").build()
        val retry = client.authenticator.authenticate(null, unauthorized)!!
        assertEquals("old", rejected)
        assertEquals("Bearer new", retry.header("Authorization"))
        val failedRetry = unauthorized.newBuilder().request(retry).priorResponse(unauthorized).build()
        assertNull(client.authenticator.authenticate(null, failedRetry))
        assertFalse(client.followRedirects)
    }

    @Test
    fun quotaAndModelCatalogParsersUseServerData() {
        val rows = parseCodexModels("""{"models":[{"slug":"visible","visibility":"list","context_window":1234},{"slug":"hidden","visibility":"hide"}]}""")
        assertEquals(listOf("visible"), rows.map { it.modelId })
        assertEquals(1234, rows.single().contextWindow)
        val quota = parseCodexUsage("""{"rate_limit":{"limit_reached":true,"primary_window":{"used_percent":100,"limit_window_seconds":18000,"reset_at":1900000000}}}""")
        assertTrue(quota.limitReached)
        assertEquals(300, quota.windows.single().windowMinutes)
        val monthly = parseGrokMonthlyUsage(json.parseToJsonElement("""{"config":{"billingPeriodStart":"2026-10-01T00:00:00Z","billingPeriodEnd":"2026-11-01T00:00:00Z","monthlyLimit":{"val":"100"},"used":{"val":"25"}}}""").jsonObject)!!
        assertEquals(25.0, monthly.windows.single().usedPercent, 0.001)
    }
}
