package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ChatCompletionsRequestObserverTest {
    private fun build(params: TextGenerationParams, stream: Boolean): JsonObject {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
        return method.invoke(
            api, listOf(UIMessage.user("original")), params,
            ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com"), stream,
        ) as JsonObject
    }

    @Test
    fun `observer sees final custom overridden payload in streaming and nonstreaming requests`() {
        val overridden = JsonArray(listOf(buildJsonObject { put("role", "user"); put("content", "overridden") }))
        for (stream in listOf(false, true)) {
            var observed: JsonObject? = null
            var calls = 0
            val params = TextGenerationParams(
                model = Model(modelId = "deepseek-test"),
                customBody = listOf(CustomBody("messages", overridden), CustomBody("tools", JsonArray(emptyList()))),
                onRequestBuilt = { observed = it; calls++ },
            )
            val result = build(params, stream)
            assertEquals(1, calls)
            assertSame(result, observed)
            assertEquals(overridden, observed?.get("messages"))
            assertEquals(JsonArray(emptyList()), observed?.get("tools"))
        }
    }

    @Test
    fun `observer failures cannot change the payload or stop request construction`() {
        val params = TextGenerationParams(model = Model(modelId = "deepseek-test"))
        assertEquals(build(params, false), build(params.copy(onRequestBuilt = { error("diagnostic failed") }), false))
    }

    @Test
    fun `local observer is not serialized into model parameters`() {
        val params = TextGenerationParams(model = Model(modelId = "test"), onRequestBuilt = {})
        val encoded = json.encodeToString(TextGenerationParams.serializer(), params)
        assertFalse(encoded.contains("onRequestBuilt"))
        val restored = json.decodeFromString(TextGenerationParams.serializer(), encoded)
        assertNull(restored.onRequestBuilt)
    }
}
