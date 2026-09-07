package me.rerere.rikkahub.data.ai

import java.io.IOException
import java.lang.reflect.Proxy
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RequestLoggingInterceptorTest {
    @Before fun clearBefore() = Logging.clear()
    @After fun clearAfter() = Logging.clear()

    private fun request() = Request.Builder()
        .url("https://user:password@example.com/secret-path?api_key=secret#private")
        .header("Authorization", "Bearer arbitrary.secret/token")
        .header("X-Custom-Key", "secret-header")
        .post(object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                fail("Diagnostics must not serialize a request body")
            }
        }).build()

    private fun chain(request: Request, proceed: (Request) -> Response): Interceptor.Chain =
        Proxy.newProxyInstance(
            Interceptor.Chain::class.java.classLoader,
            arrayOf(Interceptor.Chain::class.java),
        ) { _, method, args ->
            when (method.name) {
                "request" -> request
                "proceed" -> proceed(args!![0] as Request)
                else -> error("Unexpected chain method: ${method.name}")
            }
        } as Interceptor.Chain

    @Test fun errorResponseIsUntouchedAndPrivateDataNeverEntersLogs() {
        val request = request()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(400).message("Bad Request")
            .header("Set-Cookie", "private-cookie")
            .body("private chat and tool arguments".toResponseBody()).build()
        val actual = RequestLoggingInterceptor().intercept(chain(request) {
            assertSame(request, it)
            response
        })
        assertSame(response, actual)
        assertEquals("private chat and tool arguments", actual.body.string())
        val log = Logging.getRequestLogs().single()
        assertEquals("https://example.com/", log.url)
        assertEquals(400, log.responseCode)
        assertTrue(log.requestHeaders.isEmpty())
        assertTrue(log.responseHeaders.isEmpty())
        assertNull(log.requestBody)
        assertNull(log.responseBody)
        assertTrue(log.durationMs!! >= 0)
        assertFalse(log.toString().contains("secret"))
        assertFalse(log.toString().contains("private"))
    }

    @Test fun networkFailureIsRethrownWithoutItsPrivateMessage() {
        val failure = IOException("Bearer secret; private chat; https://user:password@example.com")
        try {
            RequestLoggingInterceptor().intercept(chain(request()) { throw failure })
            fail("Expected original network failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
        val log = Logging.getRequestLogs().single()
        assertEquals("IOException", log.error)
        assertFalse(log.toString().contains("password"))
        assertFalse(log.toString().contains("private chat"))
    }

    @Test fun localIpv6OriginKeepsItsPortWithoutLeakingThePath() {
        val request = request().newBuilder().url("http://[::1]:8080/private?key=secret").build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body("ok".toResponseBody()).build()
        RequestLoggingInterceptor().intercept(chain(request) { response }).close()
        assertEquals("http://[::1]:8080/", Logging.getRequestLogs().single().url)
    }

    @Test fun streamingResponseIsReturnedWithoutReadingIt() {
        val request = request()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK")
            .body("data: private reply\n\n".toResponseBody("text/event-stream".toMediaType()))
            .build()
        val actual = RequestLoggingInterceptor().intercept(chain(request) { response })
        assertEquals("data: private reply\n\n", actual.body.string())
        assertNull(Logging.getRequestLogs().single().responseBody)
    }
}
