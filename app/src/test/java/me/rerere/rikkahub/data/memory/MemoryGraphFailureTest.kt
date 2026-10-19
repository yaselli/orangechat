package me.rerere.rikkahub.data.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class MemoryGraphFailureTest {
    @Test
    fun `HTTP failures retain status and never expose provider bodies keys or source text`() {
        for (status in listOf(400, 401, 403, 404, 413, 422, 429, 500, 503)) {
            val error = MemoryGraphBuildFailure(MemoryGraphBuildStage.REQUEST, MemoryGraphBuildProblem.UNKNOWN,
                Exception("Failed to get response: $status secret-key private-memory"))
            val text = memoryGraphBuildFailureMessage(error)
            assertTrue(text.contains("HTTP $status"))
            assertTrue(text.contains("接口请求阶段"))
            assertFalse(text.contains("secret-key"))
            assertFalse(text.contains("private-memory"))
        }
        val echo = MemoryGraphBuildFailure(MemoryGraphBuildStage.REQUEST, MemoryGraphBuildProblem.UNKNOWN,
            Exception("echo Failed to get response: 401 secret-key"))
        assertFalse(memoryGraphBuildFailureMessage(echo).contains("HTTP 401"))
    }

    @Test
    fun `parser distinguishes invalid JSON memory ids relation fields and original quote`() {
        fun problem(raw: String, originals: Map<Int, String> = mapOf(7 to "她喜欢炸鸡")): MemoryGraphBuildProblem {
            val error = assertThrows(MemoryGraphBuildFailure::class.java) { parseMemoryGraphBuild(raw, originals) }
            assertEquals(MemoryGraphBuildStage.OUTPUT, error.stage)
            return error.problem
        }
        assertEquals(MemoryGraphBuildProblem.EMPTY_OUTPUT, problem(" "))
        assertEquals(MemoryGraphBuildProblem.OVERSIZED, problem("x".repeat(30001)))
        assertEquals(MemoryGraphBuildProblem.JSON_STRUCTURE, problem("not-json secret-key private-memory"))
        assertEquals(MemoryGraphBuildProblem.MEMORY_IDS, problem("[]"))
        assertEquals(MemoryGraphBuildProblem.MEMORY_IDS, problem("""[{"memoryId":8,"graph":null}]"""))
        assertEquals(MemoryGraphBuildProblem.MEMORY_IDS,
            problem("""[{"memoryId":7,"graph":null},{"memoryId":7,"graph":null}]""", mapOf(7 to "a", 8 to "b")))
        val relation = MemoryGraphRelation(MemoryGraphNode("她", "user"), "喜欢", MemoryGraphNode("炸鸡", "thing"),
            MemoryGraphNode("饮食", "topic"), "preference", "asserted")
        assertEquals(MemoryGraphBuildProblem.RELATION_FIELDS, problem(JsonInstant.encodeToString(listOf(
            MemoryGraphBuildFact(7, relation.copy(status = "invented"), "炸鸡")))))
        assertEquals(MemoryGraphBuildProblem.QUOTE, problem(JsonInstant.encodeToString(listOf(
            MemoryGraphBuildFact(7, relation, "原文没有的私密内容")))))
    }

    @Test
    fun `known output limit reasons are separate from malformed or empty JSON`() {
        for (reason in listOf("length", "max_tokens", "MAX_TOKENS")) {
            val error = assertThrows(MemoryGraphBuildFailure::class.java) { checkMemoryGraphFinishReason(reason) }
            assertEquals(MemoryGraphBuildProblem.TRUNCATED, error.problem)
        }
        checkMemoryGraphFinishReason(null)
        checkMemoryGraphFinishReason("stop")
    }

    @Test
    fun `safe diagnostics retain stage and prevent serializer source snippets from leaking`() {
        val error = assertThrows(MemoryGraphBuildFailure::class.java) {
            parseMemoryGraphBuild("{\"secret-key\":\"private-memory\"}", mapOf(7 to "source"))
        }
        val text = memoryGraphBuildFailureMessage(error)
        assertTrue(text.contains("结果校验阶段"))
        assertTrue(text.contains("JSON"))
        assertFalse(text.contains("secret-key"))
        assertFalse(text.contains("private-memory"))
        assertTrue(memoryGraphBuildFailureMessage(MemoryGraphBuildFailure(
            MemoryGraphBuildStage.SAVE, MemoryGraphBuildProblem.SOURCE_CHANGED)).contains("修改或删除"))
        val database = memoryGraphBuildFailureMessage(MemoryGraphBuildFailure(
            MemoryGraphBuildStage.SAVE, MemoryGraphBuildProblem.UNKNOWN, IOException("private-database-path")))
        assertTrue(database.contains("数据库"))
        assertFalse(database.contains("网络连接"))
        assertFalse(database.contains("private-database-path"))
    }

    @Test
    fun `stage wrapper keeps network failures typed and never swallows cancellation`() = runBlocking {
        try {
            memoryGraphBuildStage(MemoryGraphBuildStage.REQUEST) { throw IOException("private-url", UnknownHostException("host")) }
            fail("Expected failure")
        } catch (error: MemoryGraphBuildFailure) {
            assertTrue(memoryGraphBuildFailureMessage(error).contains("域名"))
            assertFalse(memoryGraphBuildFailureMessage(error).contains("private-url"))
        }
        val cancellation = CancellationException("cancel")
        try {
            memoryGraphBuildStage(MemoryGraphBuildStage.REQUEST) { throw cancellation }
            fail("Expected cancellation")
        } catch (error: CancellationException) { assertSame(cancellation, error) }
    }

    @Test
    fun `own request deadline becomes visible timeout without another call`() = runBlocking {
        var calls = 0
        try {
            memoryGraphBuildStage(MemoryGraphBuildStage.REQUEST) {
                calls++
                withTimeout(1) { delay(100) }
            }
            fail("Expected timeout")
        } catch (error: MemoryGraphBuildFailure) {
            assertEquals(MemoryGraphBuildProblem.TIMEOUT, error.problem)
        }
        assertEquals(1, calls)
    }

    @Test
    fun `failed statistics refresh never claims a committed graph was unsaved`() = runBlocking {
        var calls = 0
        val result = memoryGraphBuildFeedback(build = { calls++; 3 }, refresh = { error("secret refresh error") })
        assertEquals(1, calls)
        assertTrue(result.message.contains("新增 3 条关系"))
        assertTrue(result.message.contains("统计刷新失败"))
        assertFalse(result.message.contains("建图失败"))
        assertFalse(result.message.contains("secret refresh error"))
        assertNull(result.status)
    }

    @Test
    fun `build failures do not run refresh and successful feedback retains counts`() = runBlocking {
        var calls = 0
        var refreshes = 0
        val failed = memoryGraphBuildFeedback(build = {
            calls++
            throw MemoryGraphBuildFailure(MemoryGraphBuildStage.PREPARING, MemoryGraphBuildProblem.MODEL_UNAVAILABLE)
        }, refresh = { refreshes++; 0 to 0 })
        assertTrue(failed.message.contains("尚未发起模型请求"))
        assertEquals(1, calls)
        assertEquals(0, refreshes)
        val success = memoryGraphBuildFeedback(build = { calls++; 0 }, refresh = { refreshes++; 8 to 2 })
        assertEquals(8 to 2, success.status)
        assertTrue(success.message.contains("新增 0 条关系"))
        assertEquals(2, calls)
        assertEquals(1, refreshes)
    }
}
