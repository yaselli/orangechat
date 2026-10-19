package me.rerere.rikkahub.data.memory

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.providers.openai.RerankScore

@Serializable
enum class MemoryRerankState { DISABLED, NOT_CONFIGURED, NO_CANDIDATES, SUCCESS, FAILED, TIMEOUT }

@Serializable
data class MemoryCandidateTrace(
    val memoryId: Int, val history: HistoryReference? = null, val text: String,
    val score: Double? = null, val kept: Boolean = false,
)

/** Reserve each route's own quota before mixing; history can never crowd out all long-term candidates. */
fun memoryRerankCandidates(query: String, longTerm: List<RankedMemory>, history: List<RankedMemory>): List<MemoryExcerpt> {
    fun route(ranked: List<RankedMemory>): List<MemoryExcerpt> {
        val selected = mutableListOf<MemoryExcerpt>()
        for (hit in ranked) {
            if (selected.size >= 6) break
            val slice = hit.slice
            if (selected.any { it.slice.sourceKey == slice.sourceKey &&
                    it.slice.start < slice.end && slice.start < it.slice.end }) continue
            val text = MemoryTextBudget().excerpt(slice, query, 600)
            if (text.isBlank() || selected.any { it.text.trim() == text.trim() }) continue
            selected += MemoryExcerpt(slice, text)
        }
        return selected
    }
    return (route(longTerm) + route(history)).distinctBy { it.text.trim() }.take(12)
}

fun selectRerankedMemory(
    candidates: List<MemoryExcerpt>, scores: List<RerankScore>, threshold: Double, limit: Int,
): List<SavedMemoryExcerpt> {
    require(threshold.isFinite() && threshold in 0.0..1.0)
    val result = mutableListOf<SavedMemoryExcerpt>()
    for (score in scores.sortedWith(compareByDescending<RerankScore> { it.score }.thenBy { it.index })) {
        if (result.size >= limit.coerceIn(1, 4)) break
        if (score.score < threshold) continue
        val candidate = candidates.getOrNull(score.index) ?: continue
        val slice = candidate.slice
        val excerpt = SavedMemoryExcerpt(slice.memoryId, slice.revision, slice.sourceHash, candidate.text, slice.history)
        if (result.any { it.text == excerpt.text }) continue
        if (renderMemorySnapshot(result + excerpt).isNotEmpty()) result += excerpt
    }
    return result
}
