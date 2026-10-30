package me.rerere.rikkahub.data.memory

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class MemoryIndexFailureTest {
    @Test
    fun `HTTP diagnostics preserve status but never expose echoed keys or memory content`() {
        for (status in listOf(400, 401, 403, 404, 413, 422, 429, 500, 503)) {
            val text = memoryIndexFailureReason(IllegalStateException(
                "Failed to generate embedding: $status {secret-key: test-key, input: private-memory}"
            ))
            assertTrue(text.contains("HTTP $status"))
            assertFalse(text.contains("test-key"))
            assertFalse(text.contains("private-memory"))
        }
    }

    @Test
    fun `network failure categories and nested exceptions remain distinguishable`() {
        assertTrue(memoryIndexFailureReason(IOException("wrapped", UnknownHostException("secret-host"))).contains("域名"))
        assertTrue(memoryIndexFailureReason(InterruptedIOException("timeout")).contains("超时"))
        assertTrue(memoryIndexFailureReason(SSLException("secret-certificate")).contains("证书"))
        assertTrue(memoryIndexFailureReason(IOException("secret-url")).contains("网络"))
    }

    @Test
    fun `known local and response format errors are actionable without arbitrary message exposure`() {
        assertEquals("待处理记忆已变化，请重新确认索引调用次数",
            memoryIndexFailureReason(IllegalStateException("待处理记忆已变化，请重新确认索引调用次数")))
        assertTrue(memoryIndexFailureReason(IllegalStateException("Invalid embedding indices")).contains("返回格式"))
        assertFalse(memoryIndexFailureReason(IllegalStateException("secret-key and private-memory")).contains("secret-key"))
        // A provider's echoed body is not treated as an authentic local error/HTTP prefix.
        assertFalse(memoryIndexFailureReason(IllegalStateException("echo Failed to generate embedding: 401 secret-key"))
            .contains("HTTP 401"))
    }
}
