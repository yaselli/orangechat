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
            assertEquals("/repos/$UPDATE_REPOSITORY/releases", chain.request().url.encodedPath)
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

    @Test fun `new repository missing during migration falls back to the legacy name and caches success`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            paths += path
            val status = if (path == "/repos/${ProjectLinks.REPOSITORY}/releases") 404 else 200
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body("[]".toResponseBody()).build()
        }.build()
        val checker = UpdateChecker(client, UpdateChannel.RELEASE, 165, listOf("arm64-v8a"))

        assertTrue(checker.checkUpdate().toList().last() is UiState.Success)
        assertTrue(checker.checkUpdate().toList().last() is UiState.Success)
        assertEquals(ProjectLinks.updateRepositories.map { "/repos/$it/releases" }, paths)
    }

    @Test fun `unavailable old and new repositories remain an error rather than a false no update result`() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(404).message("missing").body("{}".toResponseBody()).build()
        }.build()
        val checker = UpdateChecker(client, UpdateChannel.RELEASE, 165, listOf("arm64-v8a"))
        assertTrue(checker.checkUpdate().toList().last() is UiState.Error)
        assertEquals(2, calls.get())
    }
}
