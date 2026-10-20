package me.rerere.rikkahub.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

private const val INPUT_PROFILE_KEY = "request_input_profile_v1"

@Serializable
data class InputSizeEntry(val label: String, val bytes: Long)

/** Local sizes only. No prompt text, tool arguments, schemas, provider settings or credentials. */
@Serializable
data class RequestInputProfile(
    val messageCount: Int,
    val textBytes: Long,
    val roles: List<InputSizeEntry>,
    val systemParts: List<InputSizeEntry>,
    val transformations: List<InputSizeEntry>,
    val recallBytes: Long,
    val toolCount: Int,
    val toolSchemaBytes: Long,
    val largestTools: List<InputSizeEntry>,
    val customBodyKeys: Int,
)

internal fun inputUtf8Bytes(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

/** Deliberately omit media payloads and local metadata. Providers may further filter reasoning/tools. */
internal fun inputTextBytes(messages: List<UIMessage>): Long {
    fun size(part: UIMessagePart): Long = when (part) {
        is UIMessagePart.Text -> inputUtf8Bytes(part.text)
        is UIMessagePart.Reasoning -> inputUtf8Bytes(part.reasoning)
        is UIMessagePart.Tool -> inputUtf8Bytes(part.input) + part.output.sumOf(::size)
        else -> 0L
    }
    return messages.sumOf { it.parts.sumOf(::size) }
}

internal fun inputRoleSizes(messages: List<UIMessage>): List<InputSizeEntry> =
    messages.groupBy { it.role }.map { (role, rows) -> InputSizeEntry(role.name, inputTextBytes(rows)) }

internal fun UIMessage.attachRequestInputProfile(profile: RequestInputProfile?): UIMessage {
    return attachRequestInputProfileValue(profile?.let(::encodeRequestInputProfile))
}

internal fun encodeRequestInputProfile(profile: RequestInputProfile): JsonElement =
    JsonInstant.encodeToJsonElement(RequestInputProfile.serializer(), profile)

/** Reuse one encoded value across streaming chunks instead of reserializing the profile per token. */
internal fun UIMessage.attachRequestInputProfileValue(value: JsonElement?): UIMessage =
    if (role != MessageRole.ASSISTANT || value == null) this else
        copy(localMetadata = JsonObject(localMetadata.orEmpty() + (INPUT_PROFILE_KEY to value)))

fun UIMessage.requestInputProfile(): RequestInputProfile? {
    val raw = localMetadata?.get(INPUT_PROFILE_KEY)?.toString() ?: return null
    if (raw.length > 20_000) return null
    return runCatching { JsonInstant.decodeFromString<RequestInputProfile>(raw) }.getOrNull()?.takeIf {
        it.messageCount >= 0 && it.textBytes >= 0 && it.toolCount >= 0 && it.toolSchemaBytes >= 0 &&
            it.roles.size <= 8 && it.systemParts.size <= 16 && it.transformations.size <= 32 &&
            it.largestTools.size <= 10
    }
}
