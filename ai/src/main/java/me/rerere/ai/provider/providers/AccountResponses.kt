package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.ResponseProviderCapabilities
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient

/** Fixed account endpoints; OAuth credentials are obtained at request time and never used as roulette keys. */
internal fun accountResponses(
    client: OkHttpClient,
    credentials: AccountCredentialProvider,
    setting: ProviderSetting.Account,
    baseUrl: String,
    capabilities: ResponseProviderCapabilities,
): Pair<ResponseAPI, ProviderSetting.OpenAI> {
    val authorization = Interceptor { chain ->
        val request = chain.request().newBuilder()
        runBlocking {
            request.configureAccountAuth(credentials, setting, emptyList()) { credential ->
                if (setting is ProviderSetting.ChatGPT) {
                    credential.accountId?.let { header("ChatGPT-Account-ID", it) }
                    header("originator", "codex_cli_rs")
                }
            }
        }
        val response = chain.proceed(request.build())
        val body = response.body
        // Codex's successful SSE response may omit Content-Type. Keep JSON/error responses unchanged.
        if (setting is ProviderSetting.ChatGPT && response.isSuccessful && body.contentType() == null) {
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = "text/event-stream".toMediaType()
                override fun contentLength() = body.contentLength()
                override fun source() = body.source()
            }).build()
        } else response
    }
    val authenticated = client.withAccountAuthenticator(credentials).newBuilder().apply {
        interceptors().add(0, authorization)
    }.build()
    return ResponseAPI(authenticated, capabilities) to ProviderSetting.OpenAI(
        id = setting.id, baseUrl = baseUrl, apiKey = "account-auth-interceptor", useResponseApi = true,
    )
}
