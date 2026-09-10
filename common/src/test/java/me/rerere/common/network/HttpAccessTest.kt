package me.rerere.common.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpAccessTest {
    @Test fun httpWorksWithoutApprovalOrInitialization() = verifyHttp(false)

    @Test fun httpRedirectWorksWithoutApprovingEitherAddress() = verifyHttp(true)

    private fun verifyHttp(redirect: Boolean) {
        val loopback = InetAddress.getByName("127.0.0.1")
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 2, loopback).use { server ->
                server.soTimeout = 5_000
                val peer = executor.submit {
                    repeat(if (redirect) 2 else 1) { index ->
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) { /* consume request */ }
                            val response = if (redirect && index == 0) {
                                "HTTP/1.1 302 Found\r\n" +
                                    "Location: http://second.example:${server.localPort}/reply\r\n" +
                                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
                            } else {
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                            }
                            socket.getOutputStream().write(response.toByteArray())
                        }
                    }
                }
                val client = HttpAccess.configure(OkHttpClient.Builder())
                    .dns(object : Dns {
                        override fun lookup(hostname: String) = listOf(loopback)
                    })
                    .callTimeout(5, TimeUnit.SECONDS)
                    .build()
                client.newCall(Request.Builder()
                    .url("http://first.example:${server.localPort}/chat")
                    .build()).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("ok", response.body.string())
                    assertEquals(if (redirect) "second.example" else "first.example", response.request.url.host)
                }
                peer.get(5, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
