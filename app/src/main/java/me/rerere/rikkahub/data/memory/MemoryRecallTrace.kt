package me.rerere.rikkahub.data.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.JsonInstant

private const val RECALL_TRACE_KEY = "memory_recall_v1"

@Serializable
enum class MemoryRecallState { USED, NO_MATCH, FAILED, DISABLED, LEGACY, NOT_APPLIED, STALE }

@Serializable
enum class MemoryVectorState { NOT_CONFIGURED, NO_INDEX, SUCCESS, CACHED, TIMEOUT, FAILED, UNKNOWN, NO_QUERY }

@Serializable
data class MemoryRecallDiagnostics(
    val vector: MemoryVectorState = MemoryVectorState.UNKNOWN,
    val historyKeywordsFailed: Boolean = false,
    val graph: String? = null, // Historical replies only; the graph feature has been retired.
    val rerank: MemoryRerankState = MemoryRerankState.DISABLED,
    val candidates: List<MemoryCandidateTrace> = emptyList(),
    val longTermCandidates: Int = 0,
    val historyCandidates: Int = 0,
    val retention: String? = null,
)

@Serializable
data class MemoryRecallSnapshot(
    val excerpts: List<SavedMemoryExcerpt>,
    val diagnostics: MemoryRecallDiagnostics = MemoryRecallDiagnostics(),
)

@Serializable
data class MemoryRecallTrace(
    val state: MemoryRecallState,
    val excerpts: List<SavedMemoryExcerpt> = emptyList(),
    val diagnostics: MemoryRecallDiagnostics = MemoryRecallDiagnostics(),
) {
    val degraded: Boolean get() = diagnostics.rerank in setOf(MemoryRerankState.FAILED, MemoryRerankState.TIMEOUT) ||
        diagnostics.historyKeywordsFailed ||
        diagnostics.vector in setOf(MemoryVectorState.TIMEOUT, MemoryVectorState.FAILED)
}

/** Old retry snapshots stored a bare list; reusing them must not trigger another paid search. */
internal fun decodeMemoryRecallSnapshot(raw: String): MemoryRecallSnapshot =
    if (JsonInstant.parseToJsonElement(raw) is JsonArray) {
        MemoryRecallSnapshot(JsonInstant.decodeFromString<List<SavedMemoryExcerpt>>(raw))
    } else JsonInstant.decodeFromString<MemoryRecallSnapshot>(raw)

internal fun encodeMemoryRecallTrace(trace: MemoryRecallTrace): JsonElement =
    JsonInstant.encodeToJsonElement(MemoryRecallTrace.serializer(), trace)

internal fun UIMessage.attachMemoryRecallTrace(value: JsonElement): UIMessage =
    if (role != MessageRole.ASSISTANT) this else copy(
        localMetadata = JsonObject(localMetadata.orEmpty() + (RECALL_TRACE_KEY to value)),
    )

/** Only recorded replies have an entry. Missing/invalid old data must never imply a successful search. */
fun UIMessage.memoryRecallTrace(): MemoryRecallTrace? {
    val raw = localMetadata?.get(RECALL_TRACE_KEY)?.toString() ?: return null
    if (raw.length > 40_000) return null
    return try {
        JsonInstant.decodeFromString<MemoryRecallTrace>(raw).takeIf { trace ->
            trace.excerpts.size <= 6 && trace.diagnostics.candidates.size <= 12 &&
                trace.diagnostics.candidates.all { it.text.toByteArray(Charsets.UTF_8).size <= 600 &&
                    (it.score == null || (it.score.isFinite() && it.score in 0.0..1.0)) } &&
                (trace.excerpts.isEmpty() || renderMemorySnapshot(trace.excerpts).isNotBlank()) &&
                ((trace.state == MemoryRecallState.USED) == trace.excerpts.isNotEmpty())
        }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
