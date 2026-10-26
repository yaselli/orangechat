package me.rerere.rikkahub.data.music

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class SharedMusicApiTest {
    @Test
    fun `platform requests send a separate selector without exposing it in a credential URL`() =
        runBlocking {
            val received = java.util.Collections.synchronizedList(mutableListOf<String>())
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                received.add(exchange.requestHeaders.getFirst("X-Music-Platform"))
                assertNull(exchange.requestURI.query)
                val body = """{"ok":true}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val client = OkHttpClient()
            try {
                val api = SharedMusicApi(client)
                val connection =
                    MusicConnection("http://127.0.0.1:${server.address.port}", "device-token")
                api.request(connection, listOf("status"))
                api.request(connection.copy(platform = "qq"), listOf("status"))
                assertEquals(listOf("netease", "qq"), received)
            } finally {
                server.stop(0)
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }

    @Test
    fun `self-hosted pairing uses the owner key once and keeps only the returned device credential`() =
        runBlocking {
            val ownerKey = "own-private-server-access-key"
            val deviceToken = "t".repeat(43)
            val headers = java.util.Collections.synchronizedList(mutableListOf<String>())
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                headers.add(exchange.requestHeaders.getFirst("Authorization"))
                val body =
                    (if (exchange.requestURI.path.endsWith("/sessions"))
                            """{"ok":true,"token":"$deviceToken"}"""
                        else """{"ok":true,"connected":false}""")
                        .toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val client = OkHttpClient()
            try {
                val api = SharedMusicApi(client)
                val connection = api.register("http://127.0.0.1:${server.address.port}", ownerKey)
                assertEquals(deviceToken, connection.token)
                api.request(connection, listOf("status"))
                assertEquals(listOf("Bearer $ownerKey", "Bearer $deviceToken"), headers)
            } finally {
                server.stop(0)
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }

    @Test
    fun `cancelling a slow music request cancels the HTTP call without waiting for its body`() =
        runBlocking {
            val accepted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.sendResponseHeaders(200, 2)
                accepted.countDown()
                release.await()
                runCatching { exchange.responseBody.use { it.write("{}".toByteArray()) } }
            }
            server.start()
            val client = OkHttpClient()
            try {
                val request = async {
                    SharedMusicApi(client)
                        .request(
                            MusicConnection(
                                "http://127.0.0.1:${server.address.port}",
                                "own-secret",
                            ),
                            listOf("status"),
                        )
                }
                withContext(Dispatchers.IO) {
                    check(accepted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                withTimeout(2000) { request.cancelAndJoin() }
            } finally {
                release.countDown()
                server.stop(0)
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }

    @Test
    fun `HTTPS server setting rejects embedded secrets and public plaintext endpoints`() {
        for (url in
            listOf(
                "http://example.com",
                "https://user:secret@example.com",
                "https://example.com?token=secret",
                "https://example.com#token",
            )) {
            assertThrows(IllegalArgumentException::class.java) { musicServerUrl(url) }
        }
        assertEquals("example.com", musicServerUrl("https://example.com/music").host)
        assertEquals("127.0.0.1", musicServerUrl("http://127.0.0.1:8787").host)
        assertNull(musicCoverUrl("file:///data/private.png"))
        assertNull(musicCoverUrl("https://unrelated.example/image.png"))
        assertNotNull(musicCoverUrl("https://p1.music.126.net/cover.jpg"))
    }

    @Test
    fun `a redirect never forwards the installation credential to another endpoint`() =
        runBlocking {
            val received = AtomicInteger()
            val destination = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            destination.createContext("/") { exchange ->
                received.incrementAndGet()
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.close()
            }
            destination.start()
            val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            source.createContext("/") { exchange ->
                exchange.responseHeaders.add(
                    "Location",
                    "http://127.0.0.1:${destination.address.port}/leak",
                )
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            source.start()
            try {
                val client = OkHttpClient()
                val api = SharedMusicApi(client)
                val failure = runCatching {
                    api.request(
                        MusicConnection("http://127.0.0.1:${source.address.port}", "own-secret"),
                        listOf("status"),
                    )
                }
                    .exceptionOrNull()
                assertTrue(failure is MusicApiException)
                assertEquals(0, received.get())
                assertFalse(failure!!.message.orEmpty().contains("own-secret"))
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            } finally {
                source.stop(0)
                destination.stop(0)
            }
        }

    @Test
    fun `upstream body is never used as a user-facing error`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body =
                """{"ok":false,"error":"MUSIC_FAILED","message":"cookie=do-not-leak"}"""
                    .toByteArray()
            exchange.sendResponseHeaders(500, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val api = SharedMusicApi(OkHttpClient())
            val failure = runCatching {
                api.request(
                    MusicConnection("http://127.0.0.1:${server.address.port}", "own-secret"),
                    listOf("status"),
                )
            }
                .exceptionOrNull()
            assertTrue(failure is MusicApiException)
            assertFalse(failure!!.message.orEmpty().contains("do-not-leak"))
        } finally {
            server.stop(0)
        }
    }
}
