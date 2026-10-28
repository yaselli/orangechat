package me.rerere.rikkahub.utils

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

internal const val UPDATE_REPOSITORY = ProjectLinks.REPOSITORY
internal const val UPDATE_RELEASES_URL = "https://github.com/$UPDATE_REPOSITORY/releases"
private val trustedReleaseBases = ProjectLinks.updateRepositories.map { "https://github.com/$it/releases" }

internal fun updateDownloadBase(url: String, tag: String? = null): String? = trustedReleaseBases.firstOrNull {
    url.startsWith("$it/download/" + if (tag == null) "" else "$tag/")
}

enum class UpdateChannel(val assetName: String) { RELEASE("release"), DEBUG("debug") }

@Serializable
data class UpdateDownload(
    val name: String, val url: String, val size: String,
    val bytes: Long = 0, val sha256: String? = null,
)

@Serializable
data class UpdateInfo(
    val version: String,
    val versionCode: Int,
    val changelog: String,
    val download: UpdateDownload,
    val releaseUrl: String,
)

@Serializable
internal data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
internal data class GithubAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val digest: String? = null,
)

// Asset names are generated from the APK's actual package/version/ABI by the build script.
private val updateAssetName = Regex(
    "^Xiguaban-(.+)-(\\d+)-(arm64-v8a|x86_64|universal)-(release|debug)\\.apk$"
)
private val stableVersion = Regex("\\d+\\.\\d+\\.\\d+")
private val debugVersion = Regex("\\d+\\.\\d+\\.\\d+-debug\\.\\d+")

internal fun selectAppUpdate(
    releases: List<GithubRelease>,
    channel: UpdateChannel,
    currentVersionCode: Int,
    supportedAbis: List<String>,
): UpdateInfo? = releases.flatMap { release ->
    val version = release.tagName.removePrefix("v")
    val validVersion = when (channel) {
        UpdateChannel.RELEASE -> !release.prerelease && stableVersion.matches(version)
        UpdateChannel.DEBUG -> release.prerelease && debugVersion.matches(version)
    }
    if (release.draft || !validVersion) return@flatMap emptyList()
    release.assets.mapNotNull { asset ->
        val match = updateAssetName.matchEntire(asset.name) ?: return@mapNotNull null
        val (_, assetVersion, codeText, abi, assetChannel) = match.groupValues
        val code = codeText.toIntOrNull() ?: return@mapNotNull null
        val abiRank = if (abi == "universal") supportedAbis.size else supportedAbis.indexOf(abi)
        val releaseBase = updateDownloadBase(asset.browserDownloadUrl, release.tagName)
        if (assetVersion != version || assetChannel != channel.assetName || code <= currentVersionCode ||
            abiRank < 0 || releaseBase == null || asset.size <= 0 ||
            (asset.digest != null && !asset.digest.matches(Regex("sha256:[0-9a-fA-F]{64}")))
        ) return@mapNotNull null
        val size = String.format(Locale.ROOT, "%.1f MB", asset.size / (1024.0 * 1024.0))
        abiRank to UpdateInfo(
            version = version,
            versionCode = code,
            changelog = release.body?.takeIf { it.isNotBlank() } ?: "暂无更新说明",
            download = UpdateDownload(asset.name, asset.browserDownloadUrl, size,
                asset.size, asset.digest?.substringAfter(":")?.lowercase()),
            releaseUrl = "$releaseBase/tag/${release.tagName}",
        )
    }
}.sortedWith(compareByDescending<Pair<Int, UpdateInfo>> { it.second.versionCode }.thenBy { it.first })
    .firstOrNull()?.second
