package me.rerere.rikkahub.utils

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadTransferTest {
    @get:Rule val folder = TemporaryFolder()
    private val bytes = "a complete update".toByteArray()
    private val download: UpdateDownload
        get() {
            val name = "Xiguaban-9.9.9-999-arm64-v8a-release.apk"
            val hash =
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                }
            return UpdateDownload(
                name,
                "$UPDATE_RELEASES_URL/download/v9.9.9/$name",
                "1 MB",
                bytes.size.toLong(),
                hash,
            )
        }

    private fun partial() = File(folder.root, "partial.apk")

    private fun response(
        request: Request,
        code: Int = 200,
        body: ResponseBody = bytes.toResponseBody(),
        headers: Map<String, String> = emptyMap(),
    ): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

    @Test
    fun `fresh download saves exact bytes and reports progress`() = runBlocking {
        val file = partial()
        var received = 0L
        val client =
            OkHttpClient.Builder()
                .addInterceptor {
                    assertNull(it.request().header("Range"))
                    assertEquals("identity", it.request().header("Accept-Encoding"))
                    response(it.request())
                }
                .build()
        transferAppUpdate(client, download, file) { count, total ->
            received = count
            assertEquals(bytes.size.toLong(), total)
        }
        verifyUpdateBytes(file, download)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(bytes.size.toLong(), received)
    }

    @Test
    fun `valid partial response appends from the saved offset`() = runBlocking {
        val file = partial().apply { writeBytes(bytes.copyOfRange(0, 4)) }
        val client =
            OkHttpClient.Builder()
                .addInterceptor {
                    assertEquals("bytes=4-", it.request().header("Range"))
                    response(
                        it.request(),
                        206,
                        bytes.copyOfRange(4, bytes.size).toResponseBody(),
                        mapOf("Content-Range" to "bytes 4-${bytes.lastIndex}/${bytes.size}"),
                    )
                }
                .build()
        transferAppUpdate(client, download, file) { _, _ -> }
        verifyUpdateBytes(file, download)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `server ignoring Range restarts cleanly rather than duplicating the prefix`() =
        runBlocking {
            val file = partial().apply { writeBytes("wrong prefix".toByteArray()) }
            val client = OkHttpClient.Builder().addInterceptor { response(it.request()) }.build()
            transferAppUpdate(client, download, file) { _, _ -> }
            assertArrayEquals(bytes, file.readBytes())
        }

    @Test
    fun `wrong range start total or invalid end never changes the partial file`() {
        for (range in listOf("bytes 0-16/17", "bytes 4-16/999", "bytes 4-99/17", "invalid")) {
            val prefix = bytes.copyOfRange(0, 4)
            val file = partial().apply { writeBytes(prefix) }
            val client =
                OkHttpClient.Builder()
                    .addInterceptor {
                        response(it.request(), 206, headers = mapOf("Content-Range" to range))
                    }
                    .build()
            assertThrows(InvalidUpdateException::class.java) {
                runBlocking { transferAppUpdate(client, download, file) { _, _ -> } }
            }
            assertArrayEquals(prefix, file.readBytes())
        }
    }

    @Test
    fun `interrupted body is preserved and a later request resumes it`() = runBlocking {
        val file = partial()
        var closed = false
        val brokenBody =
            object : ResponseBody() {
                override fun contentType(): MediaType? = null

                override fun contentLength() = bytes.size.toLong()

                override fun source(): BufferedSource =
                    object : Source {
                            var index = 0

                            override fun timeout() = Timeout.NONE

                            override fun close() {
                                closed = true
                            }

                            override fun read(sink: Buffer, byteCount: Long): Long {
                                if (index >= 4) throw IOException("connection interrupted")
                                val count = minOf(4 - index, byteCount.toInt())
                                sink.write(bytes, index, count)
                                index += count
                                return count.toLong()
                            }
                        }
                        .buffer()
            }
        val calls = AtomicInteger()
        val client =
            OkHttpClient.Builder()
                .addInterceptor {
                    if (calls.getAndIncrement() == 0) response(it.request(), body = brokenBody)
                    else {
                        assertEquals("bytes=4-", it.request().header("Range"))
                        response(
                            it.request(),
                            206,
                            bytes.copyOfRange(4, bytes.size).toResponseBody(),
                            mapOf("Content-Range" to "bytes 4-${bytes.lastIndex}/${bytes.size}"),
                        )
                    }
                }
                .build()
        assertThrows(IOException::class.java) {
            runBlocking { transferAppUpdate(client, download, file) { _, _ -> } }
        }
        assertTrue(closed)
        assertArrayEquals(bytes.copyOfRange(0, 4), file.readBytes())
        transferAppUpdate(client, download, file) { _, _ -> }
        verifyUpdateBytes(file, download)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `incomplete successful HTTP response remains retryable`() {
        val file = partial()
        val client =
            OkHttpClient.Builder()
                .addInterceptor {
                    response(it.request(), body = bytes.copyOfRange(0, 4).toResponseBody())
                }
                .build()
        assertThrows(IOException::class.java) {
            runBlocking { transferAppUpdate(client, download, file) { _, _ -> } }
        }
        assertEquals(4L, file.length())
    }

    @Test
    fun `untrusted redirects and HTTP downgrades are rejected before sending another request`() {
        for (url in
            listOf(
                "https://example.com/update.apk",
                "http://release-assets.githubusercontent.com/file",
                "https://release-assets.githubusercontent.com.evil.test/file",
                "https://release-assets.githubusercontent.com:444/file",
            )) {
            val calls = AtomicInteger()
            val client =
                OkHttpClient.Builder()
                    .addInterceptor {
                        calls.incrementAndGet()
                        response(it.request(), 302, headers = mapOf("Location" to url))
                    }
                    .build()
            assertThrows(InvalidUpdateException::class.java) {
                runBlocking { transferAppUpdate(client, download, partial()) { _, _ -> } }
            }
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `GitHub asset CDN redirect is supported`() = runBlocking {
        val file = partial()
        val calls = AtomicInteger()
        val client =
            OkHttpClient.Builder()
                .addInterceptor {
                    if (calls.getAndIncrement() == 0)
                        response(
                            it.request(),
                            302,
                            headers =
                                mapOf(
                                    "Location" to
                                        "https://release-assets.githubusercontent.com/asset?token=public"
                                ),
                        )
                    else {
                        assertEquals("release-assets.githubusercontent.com", it.request().url.host)
                        response(it.request())
                    }
                }
                .build()
        transferAppUpdate(client, download, file) { _, _ -> }
        assertEquals(2, calls.get())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `invalid metadata is rejected before a network request`() {
        val calls = AtomicInteger()
        val client =
            OkHttpClient.Builder()
                .addInterceptor {
                    calls.incrementAndGet()
                    response(it.request())
                }
                .build()
        for (bad in
            listOf(
                download.copy(name = "../update.apk"),
                download.copy(bytes = 0),
                download.copy(bytes = MAX_UPDATE_BYTES + 1),
                download.copy(sha256 = "wrong"),
                download.copy(url = "https://example.com/file.apk"),
                download.copy(name = "Xiguaban-.-999-arm64-v8a-release.apk"),
            )) {
            assertThrows(InvalidUpdateException::class.java) {
                runBlocking { transferAppUpdate(client, bad, partial()) { _, _ -> } }
            }
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun `oversized body and mismatched digest cannot pass verification`() {
        val client =
            OkHttpClient.Builder()
                .addInterceptor { response(it.request(), body = ByteArray(100).toResponseBody()) }
                .build()
        assertThrows(InvalidUpdateException::class.java) {
            runBlocking { transferAppUpdate(client, download, partial()) { _, _ -> } }
        }
        val file = partial().apply { writeBytes(ByteArray(bytes.size)) }
        assertThrows(InvalidUpdateException::class.java) { verifyUpdateBytes(file, download) }
    }

    @Test
    fun `expired range discards unusable prefix for the next attempt`() {
        val file = partial().apply { writeBytes(bytes.copyOfRange(0, 4)) }
        val client = OkHttpClient.Builder().addInterceptor { response(it.request(), 416) }.build()
        assertThrows(IOException::class.java) {
            runBlocking { transferAppUpdate(client, download, file) { _, _ -> } }
        }
        assertFalse(file.exists())
    }
}
