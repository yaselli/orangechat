package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeAudio
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.toChatCompletionsAudio

/** Encode first, then decide whether a message has content: UI parts may not survive modality filtering. */
internal fun encodeChatContentParts(
    parts: List<UIMessagePart>,
    supportsImage: Boolean,
    allowAudio: Boolean,
): JsonArray = buildJsonArray {
    parts.forEach { part ->
        when (part) {
            is UIMessagePart.Text -> if (part.text.isNotBlank()) {
                add(chatTextPart(part.text))
            }
            is UIMessagePart.Image -> if (supportsImage && part.url.isNotBlank()) {
                // Missing historical attachments must never turn into an invalid {type:text,text:""} block.
                // Retain the position of the attachment without pretending the model received its pixels.
                val encoded = part.encodeBase64().getOrNull()
                add(if (encoded != null) buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", encoded.base64) })
                } else chatTextPart("[Image unavailable: the attachment could not be read.]"))
            }
            is UIMessagePart.Audio -> if (part.url.isNotBlank()) {
                require(allowAudio) { "录音只能作为用户消息发送" }
                add(part.encodeAudio().toChatCompletionsAudio())
            }
            else -> Unit
        }
    }
}

private fun chatTextPart(text: String) = buildJsonObject {
    put("type", "text")
    put("text", text)
}
