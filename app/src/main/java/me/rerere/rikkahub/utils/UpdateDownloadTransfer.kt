package me.rerere.rikkahub.utils

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.http.await
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal const val MAX_UPDATE_BYTES = 256L * 1024 * 1024

internal class InvalidUpdateException(message: String) : IllegalArgumentException(message)

internal fun validateUpdateDownload(download: UpdateDownload) {
    if (
        updateDownloadBase(download.url) == null ||
            !download.name.matches(
                Regex(
                    "Xiguaban-\\d+\\.\\d+\\.\\d+(?:-debug\\.\\d+)?-\\d+-(arm64-v8a|x86_64|universal)-(release|debug)\\.apk"
                )
            ) ||
            download.bytes !in 1..MAX_UPDATE_BYTES ||
            (download.sha256 != null && !download.sha256.matches(Regex("[0-9a-fA-F]{64}")))
    ) {
        throw InvalidUpdateException("更新文件信息无效，请重新检查更新")
    }
}

internal fun trustedUpdateRedirect(url: HttpUrl): Boolean =
    url.isHttps &&
        url.port == 443 &&
        url.username.isEmpty() &&
        url.password.isEmpty() &&
        (updateDownloadBase(url.toString()) != null ||
            url.host in
                setOf(
                    "release-assets.githubusercontent.com",
                    "objects.githubusercontent.com",
                    "github-releases.githubusercontent.com",
                ))

/**
 * Range offsets are checked before appending. A server ignoring Range restarts the file cleanly.
 */
internal fun updateResponseOffset(code: Int, range: String?, offset: Long, expected: Long): Long {
    if (code == 200) return 0
    if (code != 206) throw IOException("下载失败（HTTP $code）")
    val match =
        Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(range.orEmpty())
            ?: throw InvalidUpdateException("下载源返回了无效的续传信息")
    val (start, end, total) = match.destructured
    if (
        start.toLongOrNull() != offset ||
            total.toLongOrNull() != expected ||
            end.toLongOrNull()?.let { it in offset until expected } != true
    ) {
        throw InvalidUpdateException("下载源返回的文件范围不匹配，请重新下载")
    }
    return offset
}

internal suspend fun transferAppUpdate(
    client: OkHttpClient,
    download: UpdateDownload,
    partial: File,
    progress: suspend (Long, Long) -> Unit,
) =
    withContext(Dispatchers.IO) {
        validateUpdateDownload(download)
        val network =
            client
                .newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
        if (partial.length() > download.bytes) partial.delete()
        if (partial.length() == download.bytes) return@withContext
        val offset = partial.length()
        var url = download.url.toHttpUrl()
        val activeCall = AtomicReference<Call?>()
        coroutineScope {
            // Cancellation closes a blocked socket instead of waiting for the read timeout.
            val cancellation =
                launch(Dispatchers.IO) {
                    try {
                        awaitCancellation()
                    } finally {
                        activeCall.get()?.cancel()
                    }
                }
            try {
                var redirects = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!trustedUpdateRedirect(url)) throw InvalidUpdateException("更新下载跳转地址不受支持")
                    val request =
                        Request.Builder()
                            .url(url)
                            .header("Accept-Encoding", "identity")
                            .header("User-Agent", "Xiguaban-Updater")
                            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                            .build()
                    val call = network.newCall(request)
                    activeCall.set(call)
                    val response = call.await()
                    val redirect = response.use {
                        if (response.code in listOf(301, 302, 303, 307, 308)) {
                            if (++redirects > 5) throw InvalidUpdateException("更新下载跳转过多")
                            return@use response.header("Location")?.let(url::resolve)
                                ?: throw InvalidUpdateException("下载源没有提供有效地址")
                        }
                        if (response.code == 416) {
                            partial.delete()
                            throw IOException("续传位置已失效，重试将从头下载")
                        }
                        val start =
                            updateResponseOffset(
                                response.code,
                                response.header("Content-Range"),
                                offset,
                                download.bytes,
                            )
                        val length = response.body.contentLength()
                        if (length > download.bytes - start)
                            throw InvalidUpdateException("安装包大小超出预期")
                        RandomAccessFile(partial, "rw").use { output ->
                            if (start == 0L) output.setLength(0)
                            output.seek(start)
                            var received = start
                            var lastProgress = 0L
                            progress(received, download.bytes)
                            response.body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (received + count > download.bytes)
                                        throw InvalidUpdateException("安装包大小超出预期")
                                    output.write(buffer, 0, count)
                                    received += count
                                    val now = System.nanoTime()
                                    if (now - lastProgress >= 250_000_000L) {
                                        progress(received, download.bytes)
                                        lastProgress = now
                                    }
                                }
                            }
                            output.fd.sync()
                            progress(received, download.bytes)
                            if (received != download.bytes) throw IOException("下载中断，已保留进度，可继续下载")
                        }
                        null
                    }
                    if (redirect == null) break
                    url = redirect
                }
            } finally {
                activeCall.get()?.cancel()
                cancellation.cancel()
            }
        }
    }

internal fun verifyUpdateBytes(file: File, download: UpdateDownload) {
    if (file.length() != download.bytes) throw InvalidUpdateException("安装包不完整，请继续下载")
    download.sha256?.let { expected ->
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true))
            throw InvalidUpdateException("安装包校验失败，请重新下载")
    }
}
