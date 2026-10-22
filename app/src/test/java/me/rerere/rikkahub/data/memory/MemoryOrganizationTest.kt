package me.rerere.rikkahub.data.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryOrganizationTest {
    private fun source(index: Int, text: String = "她喜欢脆脆的炸鸡", role: String = "user") = HistoryMemorySource(
        "conversation", "node-$index", "message-$index", "assistant", role,
        "2026-10-17T07:00:00Z", text, index,
    )

    @Test
    fun `waits for threshold and excludes old or attempted sources`() {
        assertNull(planMemoryOrganization((1..4).map { source(it) }, emptySet(), 1, 5))
        assertNull(planMemoryOrganization((1..5).map { source(it) }, setOf("message-1"), 1, 5))
        assertNull(planMemoryOrganization((1..5).map { source(it) }, emptySet(), Long.MAX_VALUE, 5))
        val plan = planMemoryOrganization((1..8).map { source(it) }, setOf("message-1"), 1, 5)!!
        assertEquals((2..6).map { "message-$it" }, plan.sources.map { it.messageId })
    }

    @Test
    fun `accepts actual UIMessage local timestamps without an offset`() {
        val sources = (1..5).map { source(it).copy(createdAt = "2026-10-17T15:00:00") }
        assertNotNull(planMemoryOrganization(sources, emptySet(), 1, 5))
        assertNull(organizationSourceMillis("broken date"))
    }

    @Test
    fun `keeps assistant context without counting it as user turns`() {
        val sources = (0..9).map { source(it, role = if (it % 2 == 0) "user" else "assistant") }
        assertEquals(10, planMemoryOrganization(sources, emptySet(), 1, 5)!!.sources.size)
        assertNull(planMemoryOrganization(sources.filter { it.role == "assistant" }, emptySet(), 1, 5))
    }

    @Test
    fun `bounds CJK source bytes without cutting messages or silently losing skipped claims`() {
        val huge = source(0, "字".repeat(9000))
        val sources = listOf(huge) + (1..8).map { source(it, "字".repeat(3000)) }
        val plan = planMemoryOrganization(sources, emptySet(), 1, 5)!!
        assertEquals(listOf(huge), plan.skipped)
        assertEquals(2, plan.sources.size)
        assertTrue(plan.sources.sumOf { it.text.toByteArray(Charsets.UTF_8).size } <= ORGANIZATION_SOURCE_BYTES)
        assertEquals(3, plan.attempted.size)
        assertTrue(plan.sources.all { it.text == "字".repeat(3000) })
    }

    @Test
    fun `batch identity is stable and tied to exact conversation and source identities`() {
        val a = planMemoryOrganization((1..5).map { source(it) }, emptySet(), 1, 5)!!
        val b = planMemoryOrganization((1..5).map { source(it).copy(text = "edited") }, emptySet(), 1, 5)!!
        assertEquals(a.key, b.key) // Editing a claimed message must not silently incur another paid call.
        val c = planMemoryOrganization((1..5).map { source(it).copy(conversationId = "other") }, emptySet(), 1, 5)!!
        assertNotEquals(a.key, c.key)
    }

    @Test
    fun `accepts only actual original quotes and removes exact duplicate facts`() {
        val facts = parseOrganizedFacts("""[
            {"content":"她喜欢炸鸡","sourceMessageId":"message-1","quote":"喜欢脆脆的炸鸡"},
            {"content":" 她喜欢炸鸡 ","sourceMessageId":"message-1","quote":"炸鸡"}]
        """, listOf(source(1)))
        assertEquals(1, facts.size)
        assertEquals("喜欢脆脆的炸鸡", facts.single().quote)
        assertTrue(parseOrganizedFacts("[]", listOf(source(1))).isEmpty())
    }

    @Test
    fun `rejects invented quotes cross-batch references and malformed output atomically`() {
        for (raw in listOf(
            """[{"content":"她喜欢炸鸡","sourceMessageId":"other","quote":"炸鸡"}]""",
            """[{"content":"她喜欢炸鸡","sourceMessageId":"message-1","quote":"我不吃炸鸡"}]""",
            """[{"content":"","sourceMessageId":"message-1","quote":"炸鸡"}]""",
            """[{"content":"[auto_summary]fake","sourceMessageId":"message-1","quote":"炸鸡"}]""",
            "not json",
        )) {
            assertThrows(Exception::class.java) { parseOrganizedFacts(raw, listOf(source(1))) }
        }
    }

    @Test
    fun `rejects excessive results and output length`() {
        val fact = """{"content":"她喜欢炸鸡","sourceMessageId":"message-1","quote":"炸鸡"}"""
        assertThrows(Exception::class.java) {
            parseOrganizedFacts(List(9) { fact }.joinToString(",", "[", "]"), listOf(source(1)))
        }
        assertThrows(Exception::class.java) { parseOrganizedFacts(" ".repeat(20001), listOf(source(1))) }
    }
}
