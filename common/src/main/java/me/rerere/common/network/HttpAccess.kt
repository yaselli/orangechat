package me.rerere.common.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** App-owned native HTTP clients. This is not a sandbox for WebView, shells or native libraries. */
object HttpAccess {
    private val downloadClient by lazy { configure(OkHttpClient.Builder()).build() }
    // Address approvals were removed; configured HTTP endpoints work without a grant.
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .followSslRedirects(false)

    /** Caller owns the response and must close it. */
    fun get(
        url: String,
        connectTimeoutMillis: Long = 15_000,
        readTimeoutMillis: Long = 15_000,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        return downloadClient.newBuilder()
            .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .build().newCall(request).execute()
    }
}
