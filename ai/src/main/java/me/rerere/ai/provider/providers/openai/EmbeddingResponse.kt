package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A batch response can arrive in index order different from request order. Never attach it to wrong memories. */
internal fun parseEmbeddingVectors(data: JsonArray, expectedCount: Int): List<List<Float>> {
    require(data.size == expectedCount && expectedCount > 0) { "Embedding response count mismatch" }
    val indexed = data.map { item ->
        val record = item.jsonObject
        val index = record["index"]?.jsonPrimitive?.intOrNull ?: error("Missing embedding index")
        val values = record["embedding"]?.jsonArray?.map { it.jsonPrimitive.content.toFloat() }
            ?: error("Missing embedding vector")
        index to values
    }
    require(indexed.map { it.first }.toSet() == (0 until expectedCount).toSet()) { "Invalid embedding indices" }
    val result = indexed.sortedBy { it.first }.map { it.second }
    val dimensions = result.first().size
    require(dimensions in 1..8192 && result.all { vector ->
        vector.size == dimensions && vector.all { it.isFinite() } && vector.any { it != 0f }
    }) { "Invalid embedding vectors" }
    return result
}
