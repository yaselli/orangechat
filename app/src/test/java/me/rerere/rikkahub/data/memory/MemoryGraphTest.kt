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
        assertEquals(listOf(fact), parseMemoryGraphBuild(raw, mapOf(7 to "她喜欢炸鸡")))
        assertThrows(Exception::class.java) { parseMemoryGraphBuild(raw, mapOf(8 to "她喜欢炸鸡")) }
        assertThrows(Exception::class.java) { parseMemoryGraphBuild(raw, mapOf(7 to "她喜欢奶茶")) }
        assertThrows(Exception::class.java) { parseMemoryGraphBuild("[]", mapOf(7 to "她喜欢炸鸡")) }
        assertThrows(Exception::class.java) { validateMemoryGraph(relation().copy(status = "guess")) }
        assertEquals(1, parseMemoryGraphBuild("""[{"memoryId":7,"graph":null}]""", mapOf(7 to "闲聊")).size)
    }
}
