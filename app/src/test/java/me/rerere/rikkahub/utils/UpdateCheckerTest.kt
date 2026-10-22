package me.rerere.rikkahub.utils

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class UpdateCheckerTest {
    private fun checker(calls: AtomicInteger, status: Int = 200, body: String = "[]", now: () -> Long = { 0L }): UpdateChecker {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            assertEquals("api.github.com", chain.request().url.host)
            assertEquals("/repos/yaselli/orangechat/releases", chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody()).build()
        }.build()
        return UpdateChecker(client, UpdateChannel.RELEASE, 165, listOf("arm64-v8a"), now)
    }

    @Test fun `empty releases are a successful no update result`() = runBlocking {
        val states = checker(AtomicInteger()).checkUpdate().toList()
        assertSame(UiState.Loading, states.first())
        assertNull((states.last() as UiState.Success).data)
    }

    @Test fun `switching chats reuses success while manual check bypasses the cache`() = runBlocking {
        val calls = AtomicInteger()
        val checker = checker(calls)
        checker.checkUpdate().toList()
        checker.checkUpdate().toList()
        assertEquals(1, calls.get())
        checker.checkUpdate(force = true).toList()
        assertEquals(2, calls.get())
    }

    @Test fun `success cache expires after one hour`() = runBlocking {
        var time = 0L
        val calls = AtomicInteger()
        val checker = checker(calls, now = { time })
        checker.checkUpdate().toList()
        time = 60 * 60_000L
        checker.checkUpdate().toList()
        assertEquals(2, calls.get())
    }

    @Test fun `HTTP and malformed JSON errors are exposed and not mistaken for latest version`() = runBlocking {
        assertTrue(checker(AtomicInteger(), status = 403).checkUpdate().toList().last() is UiState.Error)
        assertTrue(checker(AtomicInteger(), body = "invalid JSON").checkUpdate().toList().last() is UiState.Error)
    }

    @Test fun `failure cache limits requests but allows later retry`() = runBlocking {
        var time = 0L
        val calls = AtomicInteger()
        val checker = checker(calls, status = 503, now = { time })
        checker.checkUpdate().toList()
        checker.checkUpdate().toList()
        assertEquals(1, calls.get())
        time = 2 * 60_000L
        checker.checkUpdate().toList()
        assertEquals(2, calls.get())
    }

    @Test fun `each version can claim an automatic prompt only once per process`() {
        val checker = checker(AtomicInteger())
        assertTrue(checker.claimAutomaticPrompt(166))
        assertFalse(checker.claimAutomaticPrompt(166))
        assertTrue(checker.claimAutomaticPrompt(167))
    }
}
