package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CacheRequestDiagnosticsTest {
    private fun message(role: String, text: String) = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    private fun payload(
        messages: List<JsonObject> = listOf(message("system", "fixed"), message("user", "hello")),
        tools: List<String> = listOf("a", "b"),
        option: String = "fixed",
    ) = buildJsonObject {
        put("model", "test")
        put("messages", JsonArray(messages))
        put("tools", JsonArray(tools.map(::JsonPrimitive)))
        put("extra", option)
    }

    @Test
    fun `normal conversation growth preserves the entire previous message prefix`() {
        val tracker = CacheRequestDiagnostics()
        assertNull(tracker.compare("chat", payload()).baselineNumber)
        val result = tracker.compare("chat", payload(messages = listOf(
            message("system", "fixed"), message("user", "hello"),
            message("assistant", "hi"), message("user", "next"),
        )))
        assertEquals(1L, result.baselineNumber ?: -1L)
        assertEquals(2, result.unchangedMessages)
        assertEquals(4, result.messageCount)
        assertNull(result.firstChangedRole)
        assertFalse(result.systemChanged)
        assertFalse(result.toolsChanged)
        assertFalse(result.optionsChanged)
    }

    @Test
    fun `removing the previous turn recall is identified as a historical user content change`() {
        val tracker = CacheRequestDiagnostics()
        tracker.compare("chat", payload(messages = listOf(
            message("system", "fixed"), message("user", "hello\nrecalled evidence"),
        )))
        val result = tracker.compare("chat", payload(messages = listOf(
            message("system", "fixed"), message("user", "hello"),
            message("assistant", "hi"), message("user", "next"),
        )))
        assertEquals(1, result.unchangedMessages)
        assertEquals("USER", result.firstChangedRole)
        assertEquals("content", result.firstChangedField)
        assertFalse(result.systemChanged)
    }

    @Test
    fun `equal size system changes are detected independently of tools`() {
        val tracker = CacheRequestDiagnostics()
        tracker.compare("chat", payload())
        val result = tracker.compare("chat", payload(messages = listOf(
            message("system", "other"), message("user", "hello"),
        )))
        assertTrue(result.systemChanged)
        assertEquals("SYSTEM", result.firstChangedRole)
        assertEquals(0, result.unchangedMessages)
        assertFalse(result.toolsChanged)
    }

    @Test
    fun `tool reordering and removal are detected even when messages do not change`() {
        val tracker = CacheRequestDiagnostics()
        tracker.compare("chat", payload())
        val reordered = tracker.compare("chat", payload(tools = listOf("b", "a")))
        assertTrue(reordered.toolsChanged)
        assertEquals(0, reordered.firstChangedTool ?: -1)
        assertEquals(2, reordered.unchangedMessages)
        val removed = tracker.compare("chat", payload(tools = listOf("b")))
        assertTrue(removed.toolsChanged)
        assertEquals(1, removed.firstChangedTool ?: -1)
    }

    @Test
    fun `other effective body overrides are detected without logging their keys or values`() {
        val tracker = CacheRequestDiagnostics()
        tracker.compare("chat", payload())
        val result = tracker.compare("chat", payload(option = "private-api-key-and-prompt"))
        assertTrue(result.optionsChanged)
        assertFalse(result.summary().contains("private-api-key-and-prompt"))
        assertFalse(result.summary().contains("extra"))
    }

    @Test
    fun `truncating history is distinct from rewriting a message`() {
        val tracker = CacheRequestDiagnostics()
        tracker.compare("chat", payload())
        val result = tracker.compare("chat", payload(messages = listOf(message("system", "fixed"))))
        assertEquals(1, result.unchangedMessages)
        assertEquals(2, result.previousMessageCount)
        assertEquals(1, result.messageCount)
        assertNull(result.firstChangedRole)
        assertTrue(result.summary().contains("减少 1 条"))
    }

    @Test
    fun `interleaved conversations use separate baselines and bounded access order eviction`() {
        val tracker = CacheRequestDiagnostics(maxScopes = 2)
        tracker.compare("a", payload())
        tracker.compare("b", payload())
        assertEquals(1L, tracker.compare("a", payload()).baselineNumber ?: -1L)
        tracker.compare("c", payload())
        assertEquals(3L, tracker.compare("a", payload()).baselineNumber ?: -1L)
        assertNull(tracker.compare("b", payload()).baselineNumber)
    }

    @Test
    fun `malformed and oversized payloads are skipped without replacing a valid baseline`() {
        val tracker = CacheRequestDiagnostics(maxMessages = 2, maxTools = 2)
        tracker.compare("chat", payload())
        assertTrue(tracker.compare("chat", JsonObject(emptyMap())).skipped)
        assertTrue(tracker.compare("chat", payload(tools = listOf("a", "b", "c"))).skipped)
        assertTrue(tracker.compare("chat", payload(messages = List(3) { message("user", "x") })).skipped)
        assertEquals(1L, tracker.compare("chat", payload()).baselineNumber ?: -1L)
    }

    @Test
    fun `unicode large strings and primitive types are hashed without modifying the payload`() {
        val tracker = CacheRequestDiagnostics()
        val source = payload(messages = listOf(message("user", "她🍉\u0000".repeat(5000))))
        val before = source.toString()
        tracker.compare("chat", source)
        assertNull(tracker.compare("chat", source).firstChangedRole)
        assertEquals(before, source.toString())
        val numeric = Json.parseToJsonElement("""{"messages":[{"role":"user","content":123}]}""") as JsonObject
        val string = Json.parseToJsonElement("""{"messages":[{"role":"user","content":"123"}]}""") as JsonObject
        tracker.compare("chat", numeric)
        assertEquals("content", tracker.compare("chat", string).firstChangedField)
    }

    @Test
    fun `arbitrary role values never appear in diagnostic output and scratch buffers are erased`() {
        val tracker = CacheRequestDiagnostics()
        tracker.compare("chat", payload())
        val result = tracker.compare("chat", payload(messages = listOf(message("private-role-secret", "private-body"))))
        assertEquals("OTHER", result.firstChangedRole)
        assertFalse(result.summary().contains("private"))
        val buffer = CacheRequestDiagnostics::class.java.getDeclaredField("hashBuffer").apply { isAccessible = true }
        assertTrue((buffer.get(tracker) as ByteArray).all { it == 0.toByte() })
        val previous = CacheRequestDiagnostics::class.java.getDeclaredField("previous").apply { isAccessible = true }
        val saved = previous.get(tracker).toString()
        assertFalse(saved.contains("private-role-secret"))
        assertFalse(saved.contains("private-body"))
        assertFalse(saved.contains("chat"))
    }

    @Test
    fun `large media-like content is skipped within a fixed hashing budget`() {
        val tracker = CacheRequestDiagnostics(maxCharacters = 2000)
        tracker.compare("chat", payload())
        assertTrue(tracker.compare("chat", payload(messages = listOf(message("user", "x".repeat(3000))))).skipped)
        assertEquals(1L, tracker.compare("chat", payload()).baselineNumber ?: -1L)
        val buffer = CacheRequestDiagnostics::class.java.getDeclaredField("hashBuffer").apply { isAccessible = true }
        assertTrue((buffer.get(tracker) as ByteArray).all { it == 0.toByte() })
    }
}
