/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.utils

import android.os.Build
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
                var releases: List<GithubRelease>? = null
                for (repository in ProjectLinks.updateRepositories) {
                    val request = Request.Builder()
                        .url("https://api.github.com/repos/$repository/releases?per_page=100")
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "Xiguaban/${BuildConfig.VERSION_NAME}")
                        .build()
                    client.newCall(request).await().use { response ->
                        if (response.code == 404 && repository != ProjectLinks.updateRepositories.last()) return@use
                        check(response.isSuccessful) { "检查更新失败（HTTP ${response.code}）" }
                        releases = json.decodeFromString<List<GithubRelease>>(response.body.string())
                    }
                    if (releases != null) break
                }
                UiState.Success(selectAppUpdate(requireNotNull(releases), channel, currentVersionCode, supportedAbis))
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
}
