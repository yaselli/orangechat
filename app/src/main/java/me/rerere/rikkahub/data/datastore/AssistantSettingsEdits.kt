package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/** Merge the edited fields into the current assistant, preserving other assistants and settings. */
internal fun Settings.withAssistantEdit(previous: Assistant, desired: Assistant): Settings {
    if (previous.id != desired.id) return this
    return copy(assistants = assistants.map { current ->
        if (current.id != desired.id) current else JsonInstant.decodeFromJsonElement<Assistant>(
            mergeSettingChanges(
                JsonInstant.encodeToJsonElement(previous),
                JsonInstant.encodeToJsonElement(desired),
                JsonInstant.encodeToJsonElement(current),
            )
        )
    })
}

/** Tag definitions and the assistant's selection must be committed together before cleanup. */
internal fun Settings.withAssistantTags(id: Uuid, selected: List<Uuid>, available: List<Tag>): Settings {
    if (assistants.none { it.id == id }) return this
    val existingIds = assistantTags.map { it.id }.toSet()
    val added = available.filter { it.id in selected && it.id !in existingIds }
    return copy(
        assistantTags = assistantTags + added,
        assistants = assistants.map { if (it.id == id) it.copy(tags = selected.distinct()) else it },
    ).withoutUnusedAssistantTags()
}

internal fun Settings.withoutUnusedAssistantTags(): Settings {
    val validIds = assistantTags.map { it.id }.toSet()
    val cleaned = assistants.map { assistant ->
        assistant.copy(tags = assistant.tags.filter { it in validIds })
    }
    val usedIds = cleaned.flatMap { it.tags }.toSet()
    return copy(assistants = cleaned, assistantTags = assistantTags.filter { it.id in usedIds })
}

/** Sorting changes IDs' order only; stale UI objects must not replace current contents. */
internal fun Settings.withAssistantOrder(ids: List<Uuid>): Settings {
    val currentById = assistants.associateBy { it.id }
    val requestedIds = ids.toSet()
    return copy(assistants = ids.distinct().mapNotNull(currentById::get) +
        assistants.filter { it.id !in requestedIds })
}

internal fun Settings.withAssistantTagOrder(ids: List<Uuid>): Settings {
    val currentById = assistantTags.associateBy { it.id }
    val requestedIds = ids.toSet()
    return copy(assistantTags = ids.distinct().mapNotNull(currentById::get) +
        assistantTags.filter { it.id !in requestedIds })
}
