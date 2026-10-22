/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class UpdateChecker(
    client: OkHttpClient,
    private val channel: UpdateChannel = if (BuildConfig.APPLICATION_ID.endsWith(".debug")) {
        UpdateChannel.DEBUG
    } else UpdateChannel.RELEASE,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE.toInt(),
    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val client = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var cached: UiState<UpdateInfo?>? = null
    private var checkedAt = 0L
    private val prompted = mutableSetOf<Int>()

    /** Share checks across chat windows; successful checks are cached for one hour. */
    fun checkUpdate(force: Boolean = false): Flow<UiState<UpdateInfo?>> = flow {
        emit(UiState.Loading)
        val result = mutex.withLock {
            val previous = cached
            val cacheMillis = if (previous is UiState.Error) 2 * 60_000L else 60 * 60_000L
            if (!force && previous != null && now() - checkedAt < cacheMillis) return@withLock previous
            val next = try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$UPDATE_REPOSITORY/releases?per_page=100")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Xiguaban/${BuildConfig.VERSION_NAME}")
                    .build()
                client.newCall(request).await().use { response ->
                    check(response.isSuccessful) { "检查更新失败（HTTP ${response.code}）" }
                    val releases = json.decodeFromString<List<GithubRelease>>(response.body.string())
                    UiState.Success(selectAppUpdate(releases, channel, currentVersionCode, supportedAbis))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                UiState.Error(error)
            }
            cached = next
            checkedAt = now()
            next
        }
        emit(result)
    }.flowOn(Dispatchers.IO)

    /** At most one automatic prompt per version in this process, even after switching chats. */
    @Synchronized
    fun claimAutomaticPrompt(versionCode: Int): Boolean = prompted.add(versionCode)

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        runCatching {
            require(download.url.startsWith("$UPDATE_RELEASES_URL/download/"))
            val request = DownloadManager.Request(download.url.toUri()).apply {
                setTitle(download.name)
                setDescription("正在下载西瓜瓣更新…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
                setMimeType("application/vnd.android.package-archive")
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(context, "开始下载，完成后请从通知栏打开安装包。", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(context, "无法启动下载，正在打开下载页面。", Toast.LENGTH_SHORT).show()
            context.openUrl(UPDATE_RELEASES_URL)
        }
    }
}
