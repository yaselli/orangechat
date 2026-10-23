package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull

/** Adapts the Responses item IDs to the call IDs used by our existing message/tool loop. */
internal class ResponseToolStreamState {
    private val callIds = mutableMapOf<String, String>()
    private val callIdsByOutputIndex = mutableMapOf<Int, String>()
    private val arguments = mutableMapOf<String, String>()
    private val outputs = linkedMapOf<String, String>()
    private val emittedOutputs = mutableSetOf<String>()

    fun callId(itemId: String, outputIndex: Int? = null): String =
        callIds[itemId] ?: outputIndex?.let(callIdsByOutputIndex::get) ?: itemId

    fun functionCall(item: JsonObject, outputIndex: Int? = null): UIMessagePart.Tool {
        val callId = item["call_id"]?.jsonPrimitiveOrNull?.contentOrNull ?: error("call_id not found")
        item["id"]?.jsonPrimitiveOrNull?.contentOrNull?.let { callIds[it] = callId }
        outputIndex?.let { callIdsByOutputIndex[it] = callId }
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = item["name"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
            input = argumentSuffix(callId, item["arguments"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()),
        )
    }

    // added, arguments.done and item.done contain snapshots, not three input deltas.
    fun argumentSuffix(callId: String, value: String): String {
        val previous = arguments[callId].orEmpty()
        if (value.isEmpty()) return ""
        require(value.startsWith(previous)) { "Inconsistent Responses function arguments" }
        arguments[callId] = value
        return value.removePrefix(previous)
    }

    fun rememberOutput(item: JsonObject) {
        val callId = item["call_id"]?.jsonPrimitiveOrNull?.contentOrNull ?: return
        outputs[callId] = item.functionCallOutputText()
    }

    fun outputChunk(callId: String?): MessageChunk? {
        if (callId == null || callId !in outputs || !emittedOutputs.add(callId)) return null
        return chunk(listOf(outputPart(callId)))
    }

    fun remainingOutputs(): List<UIMessagePart.Tool> = outputs.keys
        .filter { emittedOutputs.add(it) }
        .map(::outputPart)

    private fun outputPart(callId: String) = UIMessagePart.Tool(
        toolCallId = callId,
        toolName = "",
        input = "",
        // A Text("") still marks a successfully executed tool with an empty result as executed.
        output = listOf(UIMessagePart.Text(outputs.getValue(callId))),
    )

    fun chunk(parts: List<UIMessagePart>): MessageChunk = MessageChunk(
        id = "",
        model = "",
        choices = listOf(UIMessageChoice(
            index = 0,
            delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
            message = null,
            finishReason = null,
        )),
    )
}

// Compatible gateways use either a string or an input_text content array.
internal fun JsonObject.functionCallOutputText(): String = when (val output = get("output")) {
    null, is JsonNull -> ""
    is JsonPrimitive -> output.content
    is JsonArray -> output.mapNotNull { item ->
        item.jsonObjectOrNull?.takeIf { it["type"]?.jsonPrimitiveOrNull?.contentOrNull == "input_text" }
            ?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
    }.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: output.toString()
    else -> output.toString()
}
