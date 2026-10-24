package me.rerere.ai.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MessageTimingTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `absent timing preserves legacy serialized history and memory fingerprints`() {
        val message = UIMessage.assistant("old reply")
        val encoded = json.encodeToString(message)
        assertFalse(json.parseToJsonElement(encoded).jsonObject.containsKey("generationTimeMs"))
        assertNull(json.decodeFromString<UIMessage>(encoded).generationTimeMs)
    }

    @Test
    fun `measured duration including zero survives persistence`() {
        for (duration in listOf(0L, 1_234L)) {
            val message = UIMessage.assistant("reply").copy(generationTimeMs = duration)
            assertEquals(message, json.decodeFromString<UIMessage>(json.encodeToString(message)))
        }
    }
}
