package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableRollbackSettingsTest {
    @Test
    fun configuredRerankModelStillDeserializesAfterBehaviorRollback() {
        val configured = Model(modelId = "configured-reranker", type = ModelType.RERANK)
        assertEquals(configured, JsonInstant.decodeFromString<Model>(JsonInstant.encodeToString(configured)))
    }

    @Test
    fun updatingAssistantDoesNotErasePausedMemoryConfiguration() {
        val configured = Assistant(memoryRecallEnabled = true, memoryAutoIndexEnabled = true,
            memoryRerankEnabled = true, memoryAutoOrganizeEnabled = true)
        val saved = JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(configured.copy(name = "renamed")))
        assertEquals("renamed", saved.name)
        assertTrue(saved.memoryRecallEnabled && saved.memoryAutoIndexEnabled &&
            saved.memoryRerankEnabled && saved.memoryAutoOrganizeEnabled)
    }
}
