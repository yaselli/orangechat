package me.rerere.common.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Grants are exact origins, never suffix/domain/path matches. No credentials are retained. */
class CleartextPolicy {
    @Volatile
    var allowedOrigins: Set<String> = emptySet()
        private set

    fun replaceGrants(origins: Set<String>) {
        allowedOrigins = origins.mapNotNull(::cleartextOrigin).toSet()
    }

    fun permits(url: HttpUrl): Boolean = url.isHttps || isLoopback(url.host) || origin(url) in allowedOrigins

    companion object {
        fun cleartextOrigin(value: String): String? {
            val normalized = when {
                value.startsWith("ws://", ignoreCase = true) -> "http://" + value.substring(5)
                else -> value
            }
            val url = normalized.toHttpUrlOrNull() ?: return null
            if (url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            return origin(url)
        }

        fun origin(url: HttpUrl): String = url.newBuilder()
            .username("").password("").encodedPath("/").query(null).fragment(null)
            .build().toString().removeSuffix("/")

        private fun isLoopback(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host == "::1"
    }
}

class CleartextNotAllowedException : IOException(
    "HTTP connection blocked. Open Settings > HTTP connections to approve the address, or use HTTPS. " +
        "HTTP 连接未获允许，请在设置的 HTTP 连接中确认地址，或改用 HTTPS。"
)
