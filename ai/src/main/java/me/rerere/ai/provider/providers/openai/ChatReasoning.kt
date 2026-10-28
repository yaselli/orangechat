package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private fun JsonElement?.textOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }

/**
 * Relays may return a plain string or structured visible reasoning. Never display opaque
 * signatures.
 */
internal fun chatReasoningText(message: JsonObject): String? {
    message["reasoning_content"].textOrNull()?.let {
        return it
    }
    message["reasoning"].textOrNull()?.let {
        return it
    }

    val details =
        (message["reasoning_details"] as? JsonArray).orEmpty().mapNotNull { value ->
            val detail = value as? JsonObject ?: return@mapNotNull null
            when (detail["type"].textOrNull()) {
                "reasoning.text" -> detail["text"].textOrNull()
                "reasoning.summary" -> detail["summary"].textOrNull()
                else -> null // reasoning.encrypted and unknown types are not user-visible text.
            }
        }
    if (details.isNotEmpty()) return details.joinToString("\n")

    // Mistral-compatible content arrays can contain several thinking/text blocks.
    return (message["content"] as? JsonArray)
        .orEmpty()
        .mapNotNull { value ->
            val part = value as? JsonObject ?: return@mapNotNull null
            if (part["type"].textOrNull() != "thinking") return@mapNotNull null
            part["thinking"].textOrNull()
                ?: (part["thinking"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { (it as? JsonObject)?.get("text").textOrNull() }
                    .joinToString("")
                    .takeIf { it.isNotEmpty() }
        }
        .joinToString("\n")
        .takeIf { it.isNotEmpty() }
}

internal fun chatContentText(content: JsonElement?): String {
    content.textOrNull()?.let {
        return it
    }
    return (content as? JsonArray)
        .orEmpty()
        .mapNotNull { value ->
            val part = value as? JsonObject ?: return@mapNotNull null
            when (part["type"].textOrNull()) {
                "text",
                "output_text" -> part["text"].textOrNull()
                else -> null
            }
        }
        .joinToString("")
}
