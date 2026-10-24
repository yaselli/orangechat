package me.rerere.rikkahub.data.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryRetrievalTest {
    private val budget = MemoryTextBudget()
    private fun slices(text: String, id: Int = 1, scope: String = "a", revision: Long = 1) =
        budget.slices(scope, id, revision, text)

    @Test
    fun `guessing filler alone cannot retrieve unrelated names or settings`() {
        val unrelated = slices("你猜他们叫什么呀", 1) + slices("我们可以把这个设置关掉", 2)
        assertTrue(rankKeywordMemories("你猜我吃了什么", unrelated).isEmpty())
        assertTrue(rankKeywordMemories("你我什么怎么", unrelated).isEmpty())
        assertEquals(3, rankKeywordMemories("想吃火锅", unrelated + slices("我喜欢吃辣和火锅", 3))
            .first().slice.memoryId)
        assertTrue(rankKeywordMemories("辣", slices("喜欢辣味")).isNotEmpty())
        assertTrue(rankKeywordMemories("电脑为什么打不开", slices("我喜欢吃炸鸡，因为脆脆的")).isEmpty())
    }

    @Test
    fun `CJK emoji slices keep complete original coverage with bounded overlap`() {
        val text = ("喜欢吃脆脆的炸鸡🍗。今天喝奶茶。\n").repeat(150)
        val result = slices(text)
        assertTrue(result.size > 6)
        assertEquals(0, result.first().start)
        assertEquals(text.length, result.last().end)
        result.forEach {
            assertEquals(text.substring(it.start, it.end), it.text)
            assertTrue(budget.count(it.text) <= 800)
            assertFalse(it.text.first().isLowSurrogate())
            assertFalse(it.text.last().isHighSurrogate())
        }
        result.zipWithNext().forEach { (previous, next) ->
            assertTrue(next.start > previous.start)
            assertTrue(next.start <= previous.end)
            assertTrue(budget.count(text.substring(next.start, previous.end)) <= 100)
        }
        assertEquals(result, slices(text))
    }

    @Test
    fun `source scope revision content and positions participate in identity`() {
        val original = slices("hello").single().id
        assertNotEquals(original, slices("hello", scope = "b").single().id)
        assertNotEquals(original, slices("hello", revision = 2).single().id)
        assertNotEquals(original, slices("changed").single().id)
        assertNotEquals(original, slices("hello", id = 2).single().id)
        assertTrue(slices("").isEmpty())
    }

    @Test
    fun `keyword recall handles Chinese without spaces and never treats SQL syntax as a query`() {
        val corpus = slices("我喜欢吃炸鸡因为脆脆的", 1) + slices("我工作写代码", 2)
        assertEquals(1, rankKeywordMemories("炸鸡", corpus).first().slice.memoryId)
        assertTrue(rankKeywordMemories("%_' OR 1=1 --", slices("unrelated")).isEmpty())
        assertTrue(rankKeywordMemories("", corpus).isEmpty())
    }

    @Test
    fun `RRF rewards a hit present in both routes and counts duplicates only once`() {
        val a = slices("A", 1).single()
        val b = slices("B", 2).single()
        val c = slices("C", 3).single()
        val result = fuseMemoryRanks(
            listOf(RankedMemory(a, 999.0), RankedMemory(b, 0.1), RankedMemory(b, 0.1)),
            listOf(RankedMemory(c, 1.0), RankedMemory(b, 0.9)),
        )
        assertEquals(b.id, result.first().slice.id)
        assertEquals(2.0 / 62.0, result.first().score, 0.000001)
        assertEquals(3, result.size)
        assertEquals(result, fuseMemoryRanks(
            listOf(RankedMemory(a, 999.0), RankedMemory(b, 0.1)),
            listOf(RankedMemory(c, 1.0), RankedMemory(b, 0.9)),
        ))
    }

    @Test
    fun `each route contributes at most twenty and score ties use stable IDs`() {
        val hits = (1..40).map { RankedMemory(slices("body", it).single(), 1.0) }
        assertEquals(20, fuseMemoryRanks(hits, emptyList()).size)
        val fused = fuseMemoryRanks(listOf(hits[0]), listOf(hits[1]))
        assertEquals(fused.map { it.slice.id }.sorted(), fused.map { it.slice.id })
    }

    @Test
    fun `bad vectors cannot introduce invalid rankings`() {
        assertNull(memoryCosine(listOf(1f), listOf(1f, 2f)))
        assertNull(memoryCosine(listOf(0f), listOf(1f)))
        assertNull(memoryCosine(listOf(Float.NaN), listOf(1f)))
        assertNull(memoryCosine(listOf(Float.POSITIVE_INFINITY), listOf(1f)))
        assertNull(memoryCosine(listOf(-1f), listOf(1f)))
        assertEquals(1.0, memoryCosine(listOf(1f, 2f), listOf(1f, 2f))!!, 0.000001)
    }

    @Test
    fun `excerpt finds a late hit instead of always taking the beginning`() {
        val slice = slices("a".repeat(650) + "炸鸡好吃！" + "b".repeat(100)).first()
        val excerpt = budget.excerpt(slice, "炸鸡", 400)
        assertTrue(excerpt.contains("炸鸡"))
        assertTrue(budget.count(excerpt) <= 400)
    }

    @Test
    fun `whole rendered prompt including labels stays inside budget and at most six hits`() {
        val hits = (1..20).map { RankedMemory(slices("记忆🍗。".repeat(100), it).first(), 1.0) }
        val selected = selectMemoryExcerpts("记忆", hits)
        assertTrue(selected.isNotEmpty())
        assertTrue(selected.size <= 6)
        assertTrue(budget.count(renderMemoryExcerpts(selected)) <= 3000)
        assertTrue(selectMemoryExcerpts("x", hits, allowance = 5).isEmpty())
    }

    @Test
    fun `overlapping chunks are deduplicated while distinct source memories remain eligible`() {
        val chunks = slices("炸鸡。".repeat(300))
        val ranked = listOf(RankedMemory(chunks[0], 1.0), RankedMemory(chunks[1], 0.9)) +
            listOf(RankedMemory(slices("炸鸡也好吃", 2).first(), 0.8))
        val selected = selectMemoryExcerpts("炸鸡", ranked)
        assertEquals(listOf(1, 2), selected.map { it.slice.memoryId })
    }
}
