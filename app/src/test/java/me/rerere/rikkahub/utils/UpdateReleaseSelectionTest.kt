package me.rerere.rikkahub.utils

import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseSelectionTest {
    private fun release(version: String = "2.3.1", code: Int = 166, channel: String = "release",
                        abi: String = "arm64-v8a", draft: Boolean = false): GithubRelease {
        val name = "Xiguaban-$version-$code-$abi-$channel.apk"
        return GithubRelease(
            tagName = "v$version", draft = draft, prerelease = channel == "debug", body = "Changes",
            assets = listOf(GithubAsset(name, 1048576, "$UPDATE_RELEASES_URL/download/v$version/$name")),
        )
    }

    private fun select(vararg releases: GithubRelease, channel: UpdateChannel = UpdateChannel.RELEASE,
                       abis: List<String> = listOf("arm64-v8a")) =
        selectAppUpdate(releases.toList(), channel, 165, abis)

    @Test fun `empty repository or current and older APKs do not offer updates`() {
        assertNull(select())
        assertNull(select(release(code = 165), release(code = 164)))
    }

    @Test fun `select newer stable version with correct release notes and download`() {
        val info = select(release())!!
        assertEquals("2.3.1", info.version)
        assertEquals(166, info.versionCode)
        assertEquals("Changes", info.changelog)
        assertEquals("1.0 MB", info.download.size)
        assertTrue(info.download.name.endsWith("arm64-v8a-release.apk"))
    }

    @Test fun `stable and debug channels cannot offer each others APKs`() {
        val stable = release(code = 168)
        val debug = release(version = "2.3.1-debug.1", code = 167, channel = "debug")
        assertEquals(168, select(debug, stable)!!.versionCode)
        assertEquals(167, select(stable, debug, channel = UpdateChannel.DEBUG)!!.versionCode)
        assertNull(select(debug))
        assertNull(select(stable, channel = UpdateChannel.DEBUG))
    }

    @Test fun `drafts and mismatched tag version channel or prerelease flag stay hidden`() {
        val stable = release()
        val asset = stable.assets.single()
        assertNull(select(release(draft = true)))
        assertNull(select(stable.copy(prerelease = true)))
        assertNull(select(stable.copy(tagName = "nightly")))
        assertNull(select(stable.copy(assets = listOf(asset.copy(name = asset.name.replace("2.3.1", "2.3.2"))))))
        assertNull(select(stable.copy(assets = listOf(asset.copy(name = asset.name.replace("release.apk", "debug.apk"))))))
    }

    @Test fun `compatible ABI wins and universal is a fallback`() {
        val arm = release(abi = "arm64-v8a")
        val x86 = release(abi = "x86_64")
        val universal = release(abi = "universal")
        val combined = arm.copy(assets = universal.assets + x86.assets + arm.assets)
        assertTrue(select(combined)!!.download.name.contains("arm64-v8a"))
        assertTrue(select(combined, abis = listOf("x86_64", "arm64-v8a"))!!.download.name.contains("x86_64"))
        assertTrue(select(universal)!!.download.name.contains("universal"))
        assertNull(select(x86))
    }

    @Test fun `highest Android version code wins independent of API order`() {
        assertEquals(168, select(release(code = 167), release(code = 168), release())!!.versionCode)
    }

    @Test fun `non APKs unrelated APKs invalid codes and foreign download URLs are rejected`() {
        val stable = release()
        val asset = stable.assets.single()
        for (bad in listOf(
            asset.copy(name = "source.zip"), asset.copy(name = "app-arm64-v8a-release.apk"),
            asset.copy(name = asset.name.replace("-166-", "-99999999999-")),
            asset.copy(browserDownloadUrl = "https://example.com/${asset.name}"),
            asset.copy(browserDownloadUrl = asset.browserDownloadUrl.replace("yaselli", "other")),
        )) assertNull(select(stable.copy(assets = listOf(bad))))
    }

    @Test fun `missing release notes do not hide a valid update`() {
        assertEquals("暂无更新说明", select(release().copy(body = null))!!.changelog)
    }

    @Test fun `repository rename accepts both known download paths and preserves the actual release page`() {
        val stable = release()
        val asset = stable.assets.single()
        for (repository in ProjectLinks.updateRepositories) {
            val base = "https://github.com/$repository/releases"
            val url = "$base/download/${stable.tagName}/${asset.name}"
            val update = select(stable.copy(assets = listOf(asset.copy(browserDownloadUrl = url))))!!
            assertEquals(url, update.download.url)
            assertEquals("$base/tag/${stable.tagName}", update.releaseUrl)
            assertEquals(base, updateDownloadBase(url))
        }
    }

    @Test fun `rename compatibility does not accept lookalike repositories or a different release tag`() {
        val stable = release()
        val asset = stable.assets.single()
        for (repository in listOf("other/watermelon-chat", "yaselli/watermelon-chat-fake", "yaselli/orangechat-fake")) {
            val url = "https://github.com/$repository/releases/download/${stable.tagName}/${asset.name}"
            assertNull(updateDownloadBase(url))
            assertNull(select(stable.copy(assets = listOf(asset.copy(browserDownloadUrl = url)))))
        }
        assertNull(select(stable.copy(assets = listOf(asset.copy(
            browserDownloadUrl = asset.browserDownloadUrl.replace("/v2.3.1/", "/v2.3.0/"),
        )))))
    }
}
