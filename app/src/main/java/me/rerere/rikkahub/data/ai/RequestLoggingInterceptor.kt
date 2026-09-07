/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response

/** Never read bodies or headers for diagnostics: streaming/one-shot bodies must stay untouched. */
class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAt = System.nanoTime()
        // Paths, queries and URL credentials can all contain secrets. Only keep the origin.
        val entry = LogEntry.RequestLog(
            tag = "HTTP",
            url = request.url.newBuilder().username("").password("")
                .encodedPath("/").query(null).fragment(null).build().toString(),
            method = request.method,
        )
        try {
            val response = chain.proceed(request)
            Logging.logRequest(entry.copy(
                responseCode = response.code,
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
            ))
            return response
        } catch (e: Exception) {
            Logging.logRequest(entry.copy(
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                error = e.javaClass.simpleName,
            ))
            throw e
        }
    }
}
