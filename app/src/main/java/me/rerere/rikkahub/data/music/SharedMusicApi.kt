package me.rerere.rikkahub.data.music

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal fun musicServerUrl(value: String): HttpUrl {
    val url = value.trim().trimEnd('/').toHttpUrl()
    require(
        url.scheme == "https" || url.host in listOf("localhost", "127.0.0.1", "10.0.2.2", "::1")
    ) {
        "音乐服务需要 HTTPS 地址"
    }
    require(
        url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.query == null &&
            url.fragment == null
    ) {
        "请填写不含账号、口令或查询参数的服务地址"
    }
    return url
}

internal fun musicCoverUrl(value: String): String? = runCatching {
    value.toHttpUrl().takeIf { it.isHttps && it.host.endsWith(".music.126.net") }?.toString()
}
    .getOrNull()

class MusicApiException(val code: String) :
    Exception(
        when (code) {
            "UNAUTHORIZED" -> "音乐连接已失效，请重新连接服务"
            "MUSIC_NOT_LOGGED_IN",
            "MUSIC_LOGIN_EXPIRED" -> "网易云登录已过期，请重新扫码"
            "MUSIC_UNAVAILABLE" -> "这首歌暂时无法播放"
            "MUSIC_RATE_LIMITED" -> "操作有些频繁，请稍后再试"
            "MUSIC_SERVICE_FULL",
            "MUSIC_BUSY" -> "音乐服务繁忙，请稍后再试"
            else -> "音乐请求失败，请检查服务连接后重试"
        }
    )

class SharedMusicApi(client: OkHttpClient) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    // Credentials belong to exactly one origin. No interceptor, cookie jar or redirect can forward
    // them elsewhere.
    private val http =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(18, TimeUnit.SECONDS)
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
            .authenticator(okhttp3.Authenticator.NONE)
            .proxyAuthenticator(okhttp3.Authenticator.NONE)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .build()

    suspend fun request(
        connection: MusicConnection,
        path: List<String>,
        method: String = "GET",
        body: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
        authenticated: Boolean = true,
    ): JsonObject {
        val url =
            musicServerUrl(connection.server)
                .newBuilder()
                .apply {
                    addPathSegments("api/music")
                    path.forEach(::addPathSegment)
                    query.forEach { (key, value) -> addQueryParameter(key, value) }
                }
                .build()
        val request =
            Request.Builder()
                .url(url)
                .apply {
                    if (authenticated) {
                        check(connection.token.isNotBlank()) { "请先连接音乐服务" }
                        header("Authorization", "Bearer ${connection.token}")
                    }
                    method(
                        method,
                        if (method in listOf("GET", "DELETE")) null
                        else
                            (body ?: buildJsonObject {})
                                .toString()
                                .toRequestBody("application/json".toMediaType()),
                    )
                }
                .build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive)
                            continuation.resumeWith(
                                Result.failure(MusicApiException("MUSIC_NETWORK_ERROR"))
                            )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { parseResponse(response) }
                        if (continuation.isActive) continuation.resumeWith(result)
                    }
                }
            )
        }
    }

    private fun parseResponse(response: Response): JsonObject = response.use {
        val responseBody = response.body ?: throw MusicApiException("MUSIC_FAILED")
        require(responseBody.contentLength() <= 2_000_000) { "音乐服务返回过大" }
        val source = responseBody.source()
        source.request(2_000_001)
        require(source.buffer.size <= 2_000_000) { "音乐服务返回过大" }
        val value =
            runCatching { json.parseToJsonElement(source.readUtf8()) as? JsonObject }.getOrNull()
                ?: throw MusicApiException("MUSIC_FAILED")
        if (!response.isSuccessful || value["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw MusicApiException(value["error"]?.jsonPrimitive?.contentOrNull ?: "MUSIC_FAILED")
        }
        value
    }

    suspend fun register(server: String): MusicConnection {
        val normalized = musicServerUrl(server).toString().trimEnd('/')
        val response =
            request(MusicConnection(normalized), listOf("sessions"), "POST", authenticated = false)
        val token = response["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(Regex("[A-Za-z0-9_-]{43}").matches(token)) { "音乐服务返回了无效连接" }
        return MusicConnection(normalized, token)
    }
}
