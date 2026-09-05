package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Apply only the user's changes to the latest committed settings snapshot. */
internal fun mergeSettingChanges(
    previous: JsonElement,
    desired: JsonElement,
    current: JsonElement,
): JsonElement {
    if (previous == desired) return current
    if (previous !is JsonObject || desired !is JsonObject || current !is JsonObject) {
        return desired
    }
    val merged = current.toMutableMap()
    (previous.keys + desired.keys).forEach { key ->
        val before = previous[key]
        val after = desired[key]
        if (before != after) {
            val latest = current[key]
            when {
                after == null -> merged.remove(key)
                before != null && latest != null ->
                    merged[key] = mergeSettingChanges(before, after, latest)
                else -> merged[key] = after
            }
        }
    }
    return JsonObject(merged)
}
