package me.rerere.rikkahub.data.memory

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class MemoryGraphTest {
    private fun relation(target: String = "炸鸡", topic: String = "饮食") = MemoryGraphRelation(
        MemoryGraphNode("她", "user"), "喜欢", MemoryGraphNode(target, "thing", listOf("香脆鸡块")),
        MemoryGraphNode(topic, "topic", listOf("吃饭", "吃什么")), "preference", "asserted",
    )
    private fun candidate(id: Int, target: String = "炸鸡", topic: String = "饮食") =
        MemoryGraphCandidate(id, relation(target, topic), id.toLong())

    @Test
    fun `legal single character aliases and cosmetic output differences do not reject a relation`() {
        val value = relation().copy(subject = MemoryGraphNode(" 渡 ", " ASSISTANT ",
            listOf(" 渡 ", "渡", "", "沈", "x".repeat(41), "沈渡", "kira", "extra")), status = " ASSERTED ")
        val normalized = normalizeMemoryGraph(value)
        validateMemoryGraph(normalized)
        assertEquals("渡", normalized.subject.name)
        assertEquals("assistant", normalized.subject.type)
        assertEquals(listOf("渡", "沈", "沈渡", "kira"), normalized.subject.aliases)
        assertEquals("asserted", normalized.status)
        assertEquals(value.predicate, normalized.predicate)
        val facts = inspectMemoryGraphBuild(JsonInstant.encodeToString(listOf(MemoryGraphBuildFact(7, value, "炸鸡"))),
            mapOf(7 to "炸鸡"))
        assertEquals(normalized, facts.valid.single().graph)
    }

    @Test
    fun `bad relation does not discard other valid or explicitly null rows and stays pending`() {
        val values = listOf(MemoryGraphBuildFact(7, relation(), "炸鸡"),
            MemoryGraphBuildFact(8, relation().copy(status = "private-status"), "炸鸡"),
            MemoryGraphBuildFact(9, null))
        val result = inspectMemoryGraphBuild(JsonInstant.encodeToString(values), mapOf(7 to "炸鸡", 8 to "炸鸡", 9 to "闲聊"))
        assertEquals(listOf(7, 9), result.valid.map { it.memoryId })
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("#8"))
        assertTrue(result.warnings.single().contains("status"))
        assertFalse(result.warnings.single().contains("private-status"))
    }

    @Test
    fun `quote mismatch is not repaired or accepted during partial processing`() {
        val values = listOf(MemoryGraphBuildFact(7, relation(), "炸鸡"), MemoryGraphBuildFact(8, relation(), "编造的引文"))
        val result = inspectMemoryGraphBuild(JsonInstant.encodeToString(values), mapOf(7 to "炸鸡", 8 to "原始内容"))
        assertEquals(listOf(7), result.valid.map { it.memoryId })
        assertEquals(1, result.warnings.size)
        assertFalse(result.warnings.single().contains("编造的引文"))
        assertThrows(MemoryGraphBuildFailure::class.java) {
            inspectMemoryGraphBuild(JsonInstant.encodeToString(values.takeLast(1)), mapOf(8 to "原始内容"))
        }
    }

    @Test
    fun `unknown semantic types are not guessed and all invalid output gives exact safe field`() {
        val value = relation().copy(target = MemoryGraphNode("private-name", "unknown-type"))
        val error = assertThrows(MemoryGraphBuildFailure::class.java) {
            inspectMemoryGraphBuild(JsonInstant.encodeToString(listOf(MemoryGraphBuildFact(7, value, "炸鸡"))), mapOf(7 to "炸鸡"))
        }
        assertEquals(MemoryGraphBuildProblem.RELATION_FIELDS, error.problem)
        assertEquals(MemoryGraphField.TARGET_TYPE, (error.cause as MemoryGraphFieldFailure).field)
        val message = memoryGraphBuildFailureMessage(error)
        assertTrue(message.contains("target.type"))
        assertFalse(message.contains("private-name"))
        assertFalse(message.contains("unknown-type"))
    }

    @Test
    fun `missing duplicate or foreign ids still reject before any partial result is accepted`() {
        val good = MemoryGraphBuildFact(7, relation(), "炸鸡")
        for (values in listOf(listOf(good), listOf(good, good), listOf(good, good.copy(memoryId = 99)))) {
            val error = assertThrows(MemoryGraphBuildFailure::class.java) {
                inspectMemoryGraphBuild(JsonInstant.encodeToString(values), mapOf(7 to "炸鸡", 8 to "炸鸡"))
            }
            assertEquals(MemoryGraphBuildProblem.MEMORY_IDS, error.problem)
        }
    }

    @Test
    fun `unrelated shared conversation wording cannot seed graph recall`() {
        val graph = listOf(candidate(1), candidate(2, "睡衣", "衣着"))
        assertTrue(selectMemoryGraph("你猜呀～\n嘿嘿嘿", graph).isEmpty())
        assertTrue(selectMemoryGraph("她和我\n你猜他们叫什么呀", graph).isEmpty())
    }

    @Test
    fun `entity aliases link meaning without requiring original source wording`() {
        assertEquals(listOf(1), selectMemoryGraph("香脆鸡块", listOf(candidate(1))).map { it.memoryId })
        val english = candidate(2).copy(relation = relation().copy(target = MemoryGraphNode("Java", "thing")))
        assertTrue(selectMemoryGraph("Javascript", listOf(english)).isEmpty())
        assertEquals(listOf(2), selectMemoryGraph("Java", listOf(english)).map { it.memoryId })
    }

    @Test
    fun `recent AI reply can provide topic for an elliptical user turn`() {
        val food = candidate(1)
        val clothes = candidate(2, "睡衣", "衣着").copy(relation = relation("睡衣", "衣着").copy(
            topic = MemoryGraphNode("衣着", "topic", listOf("穿什么"))))
        assertEquals(listOf(2), selectMemoryGraph("你猜呀\n你今天穿什么\n炸鸡", listOf(food, clothes)).map { it.memoryId })
        assertEquals(listOf(1), selectMemoryGraph("炸鸡\n睡衣", listOf(food, clothes)).map { it.memoryId })
    }

    @Test
    fun `a shared participant does not expand to every memory and only one hop is allowed`() {
        val first = candidate(1)
        val second = candidate(2).copy(relation = relation().copy(
            subject = MemoryGraphNode("炸鸡", "thing"), target = MemoryGraphNode("小店", "place"),
            topic = MemoryGraphNode("地点", "topic")))
        val third = candidate(3).copy(relation = relation().copy(
            subject = MemoryGraphNode("小店", "place"), target = MemoryGraphNode("老板", "person"),
            topic = MemoryGraphNode("人物", "topic")))
        val unrelated = candidate(4, "睡衣", "衣着").copy(relation = relation("睡衣", "衣着").copy(
            target = MemoryGraphNode("睡衣", "thing"), topic = MemoryGraphNode("衣着", "topic")))
        val hits = selectMemoryGraph("饮食", listOf(first, second, third, unrelated))
        assertEquals(listOf(1, 2), hits.map { it.memoryId })
    }

    @Test
    fun `specific entity outranks broad topic while duplicates and maximum are bounded`() {
        val exact = candidate(1)
        val others = (2..10).map { candidate(it, "蔬菜$it").copy(relation = relation("蔬菜$it").copy(
            target = MemoryGraphNode("蔬菜$it", "thing"))) }
        assertEquals(1, selectMemoryGraph("吃什么炸鸡", others + exact).first().memoryId)
        assertTrue(selectMemoryGraph("饮食", others + exact).size <= 4)
        assertEquals(1, selectMemoryGraph("炸鸡", listOf(exact, exact.copy(memoryId = 12))).size)
    }

    @Test
    fun `graph parser requires exact source quote complete ids and bounded valid relation schema`() {
        val fact = MemoryGraphBuildFact(7, relation(), "喜欢炸鸡")
        val raw = JsonInstant.encodeToString(listOf(fact))
        assertEquals(listOf(fact), inspectMemoryGraphBuild(raw, mapOf(7 to "她喜欢炸鸡")).valid)
        assertThrows(Exception::class.java) { inspectMemoryGraphBuild(raw, mapOf(8 to "她喜欢炸鸡")) }
        assertThrows(Exception::class.java) { inspectMemoryGraphBuild(raw, mapOf(7 to "她喜欢奶茶")) }
        assertThrows(Exception::class.java) { inspectMemoryGraphBuild("[]", mapOf(7 to "她喜欢炸鸡")) }
        assertThrows(Exception::class.java) { validateMemoryGraph(relation().copy(status = "guess")) }
        assertEquals(1, inspectMemoryGraphBuild("""[{"memoryId":7,"graph":null}]""", mapOf(7 to "闲聊")).valid.size)
    }
}
