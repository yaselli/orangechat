package me.rerere.rikkahub.data.memory

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class MemoryRecallTraceTest {
    private val source = MemoryEntity(id = 7, assistantId = "a", content = "她喜欢炸鸡，因为脆脆的", createdAt = 1234)
    private val excerpt = SavedMemoryExcerpt(7, 1, memoryHash(source.content), "她喜欢炸鸡")

    @Test
    fun `record contains exactly validated sent excerpts with source date and never modifies source messages`() {
        val user = UIMessage.user("喜欢什么")
        val deleted = excerpt.copy(memoryId = 99)
        val turn = MemoryRecallTurn("a", user.id.toString(), listOf(excerpt, deleted))
        val request = turn.apply(listOf(user), mapOf(7 to source))
        val reply = turn.annotate(UIMessage.assistant("炸鸡"))
        val trace = requireNotNull(reply.memoryRecallTrace())
        assertEquals(MemoryRecallState.USED, trace.state)
        assertEquals(1, trace.excerpts.size)
        assertEquals(excerpt.text, trace.excerpts.single().text)
        assertEquals(1234L, trace.excerpts.single().createdAtMillis)
        assertTrue((request.single().parts.last() as UIMessagePart.Text).text.contains(excerpt.text))
        assertEquals(1, user.parts.size)
        assertNull(user.localMetadata)
        assertEquals("炸鸡", (reply.parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `later source deletion cannot alter a recorded reply or claim stale sources were sent again`() {
        val user = UIMessage.user("喜欢什么")
        val turn = MemoryRecallTurn("a", user.id.toString(), listOf(excerpt))
        turn.apply(listOf(user), mapOf(7 to source))
        val first = turn.annotate(UIMessage.assistant("first"))
        assertSame(user, turn.apply(listOf(user), emptyMap()).single())
        val later = requireNotNull(turn.annotate(UIMessage.assistant("later")).memoryRecallTrace())
        assertEquals(MemoryRecallState.STALE, later.state)
        assertTrue(later.excerpts.isEmpty())
        assertEquals(excerpt.text, requireNotNull(first.memoryRecallTrace()).excerpts.single().text)
    }

    @Test
    fun `disabled failed no match and degraded keyword results remain distinct`() {
        val user = UIMessage.user("question")
        val reply = UIMessage.assistant("answer")
        for (state in listOf(MemoryRecallState.DISABLED, MemoryRecallState.LEGACY, MemoryRecallState.FAILED)) {
            val turn = MemoryRecallTurn(initialState = state)
            assertEquals(state, turn.annotate(reply).memoryRecallTrace()?.state)
        }
        val empty = MemoryRecallTurn("a", user.id.toString())
        empty.apply(listOf(user), emptyMap())
        assertEquals(MemoryRecallState.NO_MATCH, empty.annotate(reply).memoryRecallTrace()?.state)
        val degraded = MemoryRecallTurn("a", user.id.toString(), listOf(excerpt),
            diagnostics = MemoryRecallDiagnostics(MemoryVectorState.TIMEOUT))
        degraded.apply(listOf(user), mapOf(7 to source))
        val trace = requireNotNull(degraded.annotate(reply).memoryRecallTrace())
        assertEquals(MemoryRecallState.USED, trace.state)
        assertTrue(trace.degraded)
        val failed = MemoryRecallTurn("a", user.id.toString(),
            diagnostics = MemoryRecallDiagnostics(MemoryVectorState.FAILED))
        failed.apply(listOf(user), emptyMap())
        assertEquals(MemoryRecallState.FAILED, failed.annotate(reply).memoryRecallTrace()?.state)
    }

    @Test
    fun `historical references retain actual original text role date and identity`() {
        val user = UIMessage.user("以前说了什么")
        val original = HistoryMemorySource("old-chat", "node", "old-message", "a", "user",
            "2026-10-01T10:00:00", "我喜欢牛奶", 0)
        val saved = SavedMemoryExcerpt(0, 0, original.hash, original.text, original.reference)
        val boundary = HistoryRecallBoundary("new-chat", 0, "2026-10-17T10:00:00", setOf(user.id.toString()))
        val turn = MemoryRecallTurn("a", user.id.toString(), listOf(saved), "a", boundary)
        turn.apply(listOf(user), emptyMap(), mapOf(original.reference.key to original))
        val trace = requireNotNull(turn.annotate(UIMessage.assistant("answer")).memoryRecallTrace())
        assertEquals(original.reference, trace.excerpts.single().history)
        assertEquals("我喜欢牛奶", trace.excerpts.single().text)
    }

    @Test
    fun `old retry snapshots decode without pretending to know vector state and new envelopes round trip`() {
        val old = decodeMemoryRecallSnapshot(JsonInstant.encodeToString(listOf(excerpt)))
        assertEquals(listOf(excerpt), old.excerpts)
        assertEquals(MemoryVectorState.UNKNOWN, old.diagnostics.vector)
        val snapshot = MemoryRecallSnapshot(listOf(excerpt), MemoryRecallDiagnostics(MemoryVectorState.CACHED))
        assertEquals(snapshot, decodeMemoryRecallSnapshot(JsonInstant.encodeToString(snapshot)))
    }

    @Test
    fun `missing malformed oversized and inconsistent local records do not invent a recall result`() {
        val reply = UIMessage.assistant("old message")
        assertNull(reply.memoryRecallTrace())
        assertNull(reply.copy(localMetadata = buildJsonObject { put("memory_recall_v1", "broken-json") }).memoryRecallTrace())
        for (trace in listOf(
            MemoryRecallTrace(MemoryRecallState.USED),
            MemoryRecallTrace(MemoryRecallState.NO_MATCH, listOf(excerpt)),
            MemoryRecallTrace(MemoryRecallState.USED, List(7) { excerpt }),
            MemoryRecallTrace(MemoryRecallState.USED, listOf(excerpt.copy(text = "x".repeat(4000)))),
        )) assertNull(reply.attachMemoryRecallTrace(encodeMemoryRecallTrace(trace)).memoryRecallTrace())
    }

    @Test
    fun `local trace survives serialization without touching other metadata namespaces`() {
        val reply = UIMessage.assistant("answer").copy(localMetadata = buildJsonObject { put("other", "keep") })
        val recorded = reply.attachMemoryRecallTrace(encodeMemoryRecallTrace(MemoryRecallTrace(MemoryRecallState.NO_MATCH)))
        val restored = JsonInstant.decodeFromString<UIMessage>(JsonInstant.encodeToString(recorded))
        assertEquals(recorded.memoryRecallTrace(), restored.memoryRecallTrace())
        assertEquals(reply.localMetadata?.get("other"), restored.localMetadata?.get("other"))
        assertEquals(reply.parts, restored.parts)
    }
}
