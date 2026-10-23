package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.util.json
import me.rerere.common.http.jsonObjectOrNull

// Compatible relays may send data: null (or another non-object keepalive). Preserve
// malformed JSON errors and parse the whole SSE event so multiline JSON remains valid.
internal fun parseOpenAIStreamObject(data: String): JsonObject? =
    if (data.isBlank()) null else json.parseToJsonElement(data).jsonObjectOrNull
