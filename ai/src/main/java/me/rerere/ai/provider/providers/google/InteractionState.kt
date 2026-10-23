package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.*
import me.rerere.ai.util.json
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Instant

internal const val INTERACTIONS_META = "google_interactions"
internal fun signature(metadata: JsonObject?): String? =
    ((metadata?.get(INTERACTIONS_META) as? JsonObject)?.get("signature") as? JsonPrimitive)?.contentOrNull

/** Per HTTP request. Indexed steps retain full argument/signature snapshots without duplicating deltas in the UI. */
internal class InteractionState(private val fallbackModel: String) {
    private class Step(var raw: JsonObject) {
        val createdAt: Instant = Clock.System.now()
        var finishedAt: Instant? = null
        var argumentDeltas: String? = null
    }
    private val steps = linkedMapOf<Int, Step>()
    private var id = ""
    private var model = fallbackModel
    private var status: String? = null
    private var usage: TokenUsage? = null
    var completed = false
        private set

    fun accept(event: String?, data: String): MessageChunk? {
        if (completed || data.isBlank() || data == "[DONE]") return null
        val payload = json.parseToJsonElement(data) as? JsonObject ?: return null
        val type = payload.string("event_type") ?: event ?: payload.string("type")
        when (type) {
            "interaction.created", "interaction.status_update" -> {
                capture(payload["interaction"] as? JsonObject ?: payload)
                return null
            }
            "error" -> throw IOException((payload["error"] as? JsonObject)?.string("message") ?: "Gemini interaction failed")
            "interaction.completed" -> return complete(payload["interaction"] as? JsonObject ?: payload)
            "step.start" -> {
                val index = payload.int("index") ?: return null
                val raw = payload["step"] as? JsonObject ?: return null
                check(index !in steps) { "Duplicate interaction step index" }
                steps[index] = Step(raw)
            }
            "step.delta" -> {
                val index = payload.int("index") ?: return null
                val delta = payload["delta"] as? JsonObject ?: return null
                val deltaType = delta.string("type") ?: return null
                val step = steps.getOrPut(index) { Step(buildJsonObject {
                    put("type", when (deltaType) {
                        "text", "image", "text_annotation_delta" -> "model_output"
                        "thought_signature", "thought_summary" -> "thought"
                        "arguments_delta" -> "function_call"
                        else -> deltaType
                    })
                }) }
                when (deltaType) {
                    "text", "image" -> appendContent(step, "content", delta)
                    "text_annotation_delta" -> {
                        val content = (step.raw["content"] as? JsonArray).orEmpty().toMutableList()
                        val last = content.lastOrNull() as? JsonObject
                        if (last != null) content[content.lastIndex] = JsonObject(last + ("annotations" to
                            JsonArray((last["annotations"] as? JsonArray).orEmpty() + (delta["annotations"] as? JsonArray).orEmpty())))
                        step.raw = JsonObject(step.raw + ("content" to JsonArray(content)))
                    }
                    "thought_summary" -> (delta["content"] as? JsonObject)?.let { appendContent(step, "summary", it) }
                    "thought_signature" -> delta["signature"]?.let { step.raw = JsonObject(step.raw + ("signature" to it)) }
                    "arguments_delta" -> step.argumentDeltas = step.argumentDeltas.orEmpty() + delta.string("arguments").orEmpty()
                    else -> step.raw = JsonObject(step.raw + delta.filterKeys { it != "type" })
                }
            }
            "step.stop" -> {
                val index = payload.int("index") ?: return null
                val step = steps[index] ?: return null
                (payload["step"] as? JsonObject)?.let { step.raw = it; step.argumentDeltas = null }
                step.finishedAt = Clock.System.now()
            }
            else -> return null
        }
        return snapshot()
    }

    fun complete(interaction: JsonObject): MessageChunk {
        capture(interaction)
        check(status !in setOf("failed", "cancelled")) { "Gemini interaction ${status.orEmpty()}" }
        (interaction["steps"] as? JsonArray)?.let { finalSteps ->
            val previous = steps.toMap()
            steps.clear()
            finalSteps.forEachIndexed { index, value ->
                val raw = value as? JsonObject ?: return@forEachIndexed
                val step = previous[index] ?: Step(raw)
                step.raw = raw
                step.argumentDeltas = null
                steps[index] = step
            }
        }
        steps.values.forEach { if (it.finishedAt == null) it.finishedAt = Clock.System.now() }
        completed = true
        return snapshot()
    }

    private fun capture(interaction: JsonObject) {
        interaction.string("id")?.let { id = it }
        interaction.string("model")?.let { model = it }
        interaction.string("status")?.let { status = it }
        (interaction["usage"] as? JsonObject)?.let { value ->
            fun count(key: String) = (value.int(key) ?: 0).coerceAtLeast(0)
            val output = (count("total_output_tokens").toLong() + count("total_thought_tokens")).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            usage = TokenUsage(count("total_input_tokens"), output, count("total_cached_tokens"),
                value.int("total_tokens") ?: (count("total_input_tokens").toLong() + output).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private fun appendContent(step: Step, key: String, delta: JsonObject) {
        val list = (step.raw[key] as? JsonArray).orEmpty().toMutableList()
        val last = list.lastOrNull() as? JsonObject
        if (delta.string("type") == "text" && last?.string("type") == "text") {
            list[list.lastIndex] = JsonObject(last + mapOf("text" to JsonPrimitive(last.string("text").orEmpty() + delta.string("text").orEmpty()),
                "annotations" to JsonArray((last["annotations"] as? JsonArray).orEmpty() + (delta["annotations"] as? JsonArray).orEmpty())))
        } else list.add(delta)
        step.raw = JsonObject(step.raw + (key to JsonArray(list)))
    }

    private fun snapshot(): MessageChunk {
        val parts = mutableListOf<UIMessagePart>()
        val annotations = mutableListOf<UIMessageAnnotation>()
        val servers = linkedMapOf<String, Int>()
        steps.toSortedMap().forEach { (index, state) ->
            val raw = state.raw
            val type = raw.string("type").orEmpty()
            val sig = raw.string("signature")?.let { buildJsonObject { put("signature", it) } }
            val sigMetadata = sig?.let { buildJsonObject { put(INTERACTIONS_META, it) } }
            when {
                type == "thought" -> parts.add(UIMessagePart.Reasoning(
                    (raw["summary"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("text") }.joinToString(""),
                    state.createdAt, state.finishedAt, sigMetadata))
                type == "model_output" -> (raw["content"] as? JsonArray).orEmpty().forEach { element ->
                    val content = element as? JsonObject ?: return@forEach
                    when (content.string("type")) {
                        "text" -> parts.add(UIMessagePart.Text(content.string("text").orEmpty()))
                        "image" -> {
                            val url = content.string("uri") ?: content.string("data")?.let {
                                "data:${content.string("mime_type") ?: "image/png"};base64,$it"
                            }
                            if (url != null) parts.add(UIMessagePart.Image(url))
                        }
                    }
                    (content["annotations"] as? JsonArray).orEmpty().forEach { element ->
                        val citation = element as? JsonObject ?: return@forEach
                        if (citation.string("type") == "url_citation") citation.string("url")?.let { url ->
                            annotations.add(UIMessageAnnotation.UrlCitation(citation.string("title") ?: url, url))
                        }
                    }
                }
                type == "function_call" -> {
                    val input = state.argumentDeltas ?: raw["arguments"]?.let {
                        if (it is JsonPrimitive && it.isString) it.content else it.toString()
                    } ?: "{}"
                    if (completed) {
                        check(raw.string("name")?.isNotBlank() == true) { "Gemini function name missing" }
                        check(json.parseToJsonElement(input) is JsonObject) { "Gemini function arguments must be an object" }
                    }
                    parts.add(UIMessagePart.Tool(raw.string("id") ?: "$id:tool-$index", raw.string("name").orEmpty(), input,
                        metadata = sigMetadata))
                }
                type.endsWith("_call") -> {
                    val callId = raw.string("id") ?: "$id:server-$index"
                    servers[callId] = parts.size
                    parts.add(UIMessagePart.Tool(callId, type.removeSuffix("_call"), raw["arguments"]?.toString() ?: "{}",
                        output = listOf(UIMessagePart.Text("{\"status\":\"in_progress\"}")),
                        metadata = buildJsonObject { put(INTERACTIONS_META, buildJsonObject { put("call", raw) }) }))
                }
                type.endsWith("_result") -> {
                    val partIndex = servers[raw.string("call_id")] ?: return@forEach
                    val part = parts[partIndex] as UIMessagePart.Tool
                    val meta = part.metadata!![INTERACTIONS_META] as JsonObject
                    parts[partIndex] = part.copy(output = listOf(UIMessagePart.Text(raw.toString())),
                        metadata = buildJsonObject { put(INTERACTIONS_META, JsonObject(meta + ("result" to raw))) })
                }
            }
        }
        return MessageChunk(id, model, listOf(UIMessageChoice(0, null,
            UIMessage(role = MessageRole.ASSISTANT, parts = parts, annotations = annotations.distinct()),
            status.takeIf { completed })), usage, replaceContent = true)
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
