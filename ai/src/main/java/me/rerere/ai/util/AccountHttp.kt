package me.rerere.ai.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

internal suspend fun OkHttpClient.fetchBody(request: Request): String = newCall(request).await().use {
    if (!it.isSuccessful) throw IOException("Account request failed (HTTP ${it.code})")
    it.body.string()
}

internal fun JsonObject.stringOrNull(key: String): String? = get(key)?.jsonPrimitiveOrNull?.contentOrNull
