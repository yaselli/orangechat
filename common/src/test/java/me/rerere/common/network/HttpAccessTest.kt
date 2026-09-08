package me.rerere.common.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpAccessTest {
    @Test fun unapprovedRequestIsBlockedBeforeDnsOrBodyTransmission() {
        val client = HttpAccess.configure(OkHttpClient.Builder())
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    throw AssertionError("Blocked request reached DNS")
            })
            .build()
        val request = Request.Builder().url("http://denied.example/private?key=secret").build()
        try {
            client.newCall(request).execute().close()
            fail("Expected HTTP rejection")
        } catch (expected: CleartextNotAllowedException) {
            assertFalse(expected.message.orEmpty().contains("secret"))
            assertTrue(HttpAccess.blocked.value.contains("http://denied.example"))
            assertFalse(HttpAccess.blocked.value.any { it.contains("private") || it.contains("secret") })
        }
    }

    @Test fun redirectCannotSendHeadersToAnUnapprovedOrigin() {
        val executor = Executors.newSingleThreadExecutor()
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 2, loopback).use { server ->
            server.soTimeout = 5_000
            val peer = executor.submit<Int> {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                    socket.getOutputStream().write(
                        ("HTTP/1.1 302 Found\r\n" +
                            "Location: http://redirect-denied.example:${server.localPort}/secret\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                }
                // OkHttp opens the connection before network interceptors; no HTTP bytes may follow.
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    socket.getInputStream().read()
                }
            }
            try {
                val client = HttpAccess.configure(OkHttpClient.Builder())
                    .dns(object : Dns {
                        override fun lookup(hostname: String) = listOf(loopback)
                    })
                    .callTimeout(5, TimeUnit.SECONDS)
                    .build()
                try {
                    client.newCall(Request.Builder()
                        .url("http://127.0.0.1:${server.localPort}")
                        .header("X-Api-Key", "must-not-reach-redirect")
                        .build()).execute().close()
                    fail("Expected redirect rejection")
                } catch (_: CleartextNotAllowedException) {
                    assertEquals(-1, peer.get(5, TimeUnit.SECONDS).toInt())
                }
                assertFalse(client.followSslRedirects)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
