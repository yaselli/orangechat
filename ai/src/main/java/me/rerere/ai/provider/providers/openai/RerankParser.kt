package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.double

data class RerankScore(val index: Int, val score: Double)

/** Index identity is authoritative; the server must never substitute arbitrary returned document text. */
fun parseRerankScores(raw: String, count: Int): List<RerankScore> {
    require(raw.length <= 65_536 && count in 1..12)
    val values = Json.parseToJsonElement(raw).jsonObject.getValue("results").jsonArray
    require(values.size <= count)
    val scores = values.map { value ->
        val row = value.jsonObject
        RerankScore(row.getValue("index").jsonPrimitive.int, row.getValue("relevance_score").jsonPrimitive.double)
    }
    require(scores.all { it.index in 0 until count && it.score.isFinite() && it.score in 0.0..1.0 })
    require(scores.map { it.index }.distinct().size == scores.size)
    return scores.sortedWith(compareByDescending<RerankScore> { it.score }.thenBy { it.index })
}
