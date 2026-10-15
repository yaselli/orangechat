package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Tag
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantSettingsEditsTest {
    @Test
    fun `assistant edit preserves current global settings and other assistants`() {
        val before = Assistant(name = "before")
        val other = Assistant(name = "other")
        val current = Settings(
            assistants = listOf(other, before),
            enableWebSearch = true,
            dynamicColor = false,
            launchCount = 9,
        )
        val updated = current.withAssistantEdit(before, before.copy(name = "edited"))
        assertEquals(current.copy(assistants = listOf(other, before.copy(name = "edited"))), updated)
        assertSame(other, updated.assistants.first())
    }

    @Test
    fun `stale name edit preserves newer model tool and split bubble settings on same assistant`() {
        val before = Assistant(name = "before")
        val latest = before.copy(chatModelId = Uuid.random(), splitBubbleByLine = true, enableMemory = true)
        val current = Settings(assistants = listOf(latest))
        assertEquals(
            latest.copy(name = "edited"),
            current.withAssistantEdit(before, before.copy(name = "edited")).assistants.single(),
        )
    }

    @Test
    fun `successive queued edits merge into the latest assistant`() {
        val before = Assistant()
        val current = Settings(assistants = listOf(before))
            .withAssistantEdit(before, before.copy(name = "edited"))
            .withAssistantEdit(before, before.copy(systemPrompt = "new prompt"))
        assertEquals(before.copy(name = "edited", systemPrompt = "new prompt"), current.assistants.single())
    }

    @Test
    fun `explicit resets and disable are saved without restoring stale unrelated values`() {
        val before = Assistant(name = "old", temperature = 0.7f, splitBubbleByLine = true)
        val latest = before.copy(enableMemory = true, systemPrompt = "latest")
        val desired = before.copy(name = "", temperature = null, splitBubbleByLine = false)
        assertEquals(
            desired.copy(enableMemory = true, systemPrompt = "latest"),
            Settings(assistants = listOf(latest)).withAssistantEdit(before, desired).assistants.single(),
        )
    }

    @Test
    fun `unchanged stale edit cannot restore old avatar or background`() {
        val before = Assistant(avatar = Avatar.Image("file:///old"), background = "file:///old-bg")
        val latest = before.copy(avatar = Avatar.Image("file:///new"), background = "file:///new-bg")
        assertEquals(
            latest.copy(name = "edited"),
            Settings(assistants = listOf(latest))
                .withAssistantEdit(before, before.copy(name = "edited")).assistants.single(),
        )
    }

    @Test
    fun `late edit never recreates a deleted assistant or edits a different id`() {
        val deleted = Assistant(name = "deleted")
        val other = Assistant(name = "other")
        val current = Settings(assistants = listOf(other))
        assertEquals(current, current.withAssistantEdit(deleted, deleted.copy(name = "late")))
        assertSame(current, current.withAssistantEdit(deleted, other))
    }

    @Test
    fun `reordering preserves concurrent edits additions and deletions`() {
        val first = Assistant(name = "first")
        val second = Assistant(name = "second")
        val deleted = Assistant(name = "deleted")
        val added = Assistant(name = "added")
        val latestFirst = first.copy(systemPrompt = "latest")
        val current = Settings(assistants = listOf(latestFirst, second, added), launchCount = 4)
        assertEquals(
            current.copy(assistants = listOf(second, latestFirst, added)),
            current.withAssistantOrder(listOf(second.id, deleted.id, first.id, first.id)),
        )
    }

    @Test
    fun `tag selection and definition are committed together while retaining another assistants tags`() {
        val old = Tag(Uuid.random(), "old")
        val added = Tag(Uuid.random(), "new")
        val concurrent = Tag(Uuid.random(), "concurrent")
        val first = Assistant(tags = listOf(old.id), splitBubbleByLine = true)
        val other = Assistant(tags = listOf(concurrent.id))
        val current = Settings(assistants = listOf(first, other), assistantTags = listOf(old, concurrent))
        val updated = current.withAssistantTags(first.id, listOf(added.id), listOf(old, added))
        assertEquals(listOf(concurrent, added), updated.assistantTags)
        assertEquals(listOf(first.copy(tags = listOf(added.id)), other), updated.assistants)
        assertEquals(current.copy(assistants = updated.assistants, assistantTags = updated.assistantTags), updated)
    }

    @Test
    fun `tag order preserves current names additions and deleted tags`() {
        val first = Tag(Uuid.random(), "renamed")
        val second = Tag(Uuid.random(), "second")
        val added = Tag(Uuid.random(), "added")
        val current = Settings(assistantTags = listOf(first, second, added))
        assertEquals(
            current.copy(assistantTags = listOf(second, first, added)),
            current.withAssistantTagOrder(listOf(second.id, Uuid.random(), first.id)),
        )
    }

    @Test
    fun `tag selection for deleted assistant neither adds definitions nor cleans other data`() {
        val current = Settings(assistants = emptyList())
        val tag = Tag(Uuid.random(), "unused")
        assertSame(current, current.withAssistantTags(Uuid.random(), listOf(tag.id), listOf(tag)))
    }
}
