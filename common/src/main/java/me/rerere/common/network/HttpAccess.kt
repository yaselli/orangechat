package me.rerere.common.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** App-owned native HTTP clients. This is not a sandbox for WebView, shells or native libraries. */
object HttpAccess {
    private val downloadClient by lazy { configure(OkHttpClient.Builder()).build() }
    private val policy = CleartextPolicy()
    private var preferences: SharedPreferences? = null
    private val mutableAllowed = MutableStateFlow<Set<String>>(emptySet())
    private val mutableBlocked = MutableStateFlow<Set<String>>(emptySet())
    val allowed = mutableAllowed.asStateFlow()
    val blocked = mutableBlocked.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("http_access", Context.MODE_PRIVATE)
        policy.replaceGrants(preferences?.getStringSet("origins", emptySet()).orEmpty())
        mutableAllowed.value = policy.allowedOrigins
    }

    @Synchronized
    fun allow(value: String) {
        val origin = requireNotNull(CleartextPolicy.cleartextOrigin(value))
        save(policy.allowedOrigins + origin)
        mutableBlocked.value = mutableBlocked.value - origin
    }

    @Synchronized
    fun revoke(origin: String) = save(policy.allowedOrigins - origin)

    private fun save(origins: Set<String>) {
        checkNotNull(preferences) { "HTTP policy is not initialized" }
            .edit().putStringSet("origins", origins).apply()
        policy.replaceGrants(origins)
        mutableAllowed.value = policy.allowedOrigins
    }

    @Synchronized
    fun requireAllowed(url: HttpUrl) {
        if (policy.permits(url)) return
        val origin = CleartextPolicy.origin(url)
        // Only origin is retained; paths, queries, credentials and request content are not recorded.
        mutableBlocked.value = (mutableBlocked.value + origin).takeLastOrigins(20)
        throw CleartextNotAllowedException()
    }

    private fun Set<String>.takeLastOrigins(limit: Int) = toList().takeLast(limit).toSet()

    private val guard = Interceptor { chain ->
        requireAllowed(chain.request().url)
        chain.proceed(chain.request())
    }

    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        // Application guard covers cached calls and WebSocket handshakes too.
        .addInterceptor(guard)
        // Network guard rechecks each same-scheme redirect and revocations before transmission.
        .addNetworkInterceptor(guard)
        // Never silently downgrade HTTPS to HTTP (or change schemes during a redirect).
        .followSslRedirects(false)

    /** Caller owns the response and must close it. Redirects are checked by the same guards. */
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
