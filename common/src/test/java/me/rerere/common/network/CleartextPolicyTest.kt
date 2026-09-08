package me.rerere.common.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class CleartextPolicyTest {
    @Test fun defaultAllowsTlsAndLiteralLoopbackOnly() {
        val policy = CleartextPolicy()
        for (url in listOf("https://example.com", "http://127.0.0.1:8080", "http://[::1]:8080", "http://localhost:3000")) {
            assertTrue(url, policy.permits(url.toHttpUrl()))
        }
        for (url in listOf("http://example.com", "http://192.168.1.2", "http://localhost.evil.example", "http://127.0.0.2")) {
            assertFalse(url, policy.permits(url.toHttpUrl()))
        }
    }

    @Test fun grantsAreExactHostAndPortAndCanBeRevoked() {
        val policy = CleartextPolicy()
        policy.replaceGrants(setOf("http://EXAMPLE.com:8080/v1?key=secret#fragment"))
        assertEquals(setOf("http://example.com:8080"), policy.allowedOrigins)
        assertTrue(policy.permits("http://example.com:8080/other".toHttpUrl()))
        for (url in listOf("http://example.com", "http://sub.example.com:8080", "http://example.com.evil:8080")) {
            assertFalse(policy.permits(url.toHttpUrl()))
        }
        policy.replaceGrants(emptySet())
        assertFalse(policy.permits("http://example.com:8080".toHttpUrl()))
    }

    @Test fun credentialsAndInvalidSchemesCannotBecomeGrants() {
        for (value in listOf("ftp://example.com", "https://example.com", "http://user:secret@example.com", "not a URL")) {
            assertNull(CleartextPolicy.cleartextOrigin(value))
        }
        assertEquals("http://[2001:db8::1]:9000", CleartextPolicy.cleartextOrigin("http://[2001:db8::1]:9000/path"))
        assertEquals("http://example.com:9000", CleartextPolicy.cleartextOrigin("ws://example.com:9000/ws"))
    }

    @Test fun errorDoesNotExposeUpstreamAddressOrSecrets() {
        val error = CleartextNotAllowedException()
        assertFalse(error.message.orEmpty().contains("example.com"))
        assertTrue(error.message.orEmpty().contains("HTTPS"))
    }
}
