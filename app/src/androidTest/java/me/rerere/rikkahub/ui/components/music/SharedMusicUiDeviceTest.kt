package me.rerere.rikkahub.ui.components.music

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.music.*
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Narrow phone, large text, dark mode and actual lyric selection; no music account or model calls.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class SharedMusicUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val manager by lazy {
        SharedMusicManager(context, SharedMusicCredentials(context), SharedMusicApi(OkHttpClient()))
    }
    private val song =
        MusicSong(
            "1",
            "和你一起听的歌",
            "测试歌手",
            durationMs = 240_000,
            lyrics = (0..7).map { MusicLyric(it, it * 15_000L, "这是第 ${it + 1} 句测试歌词") },
        )
    private val playback
        get() =
            MusicPlayback(
                "role",
                "chat",
                MusicQueueItem("queue", "1", addedBy = "ta", note = "想把这首歌的温柔分给你。"),
                song,
                state = "playing",
                positionMs = 45_000,
                observedAt = System.currentTimeMillis(),
            )

    @Test
    fun smallPhoneCardsRenderInLightAndDarkWithLargeText() {
        var dark by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface {
                        Column(
                            Modifier.width(320.dp).fillMaxHeight().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MusicPlaybackControls(playback, manager, {}, compact = true)
                            SharedMusicCards(listOf(song.cardPart("想把这首歌的温柔分给你。")), "role", manager)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("分你一只耳机").assertIsDisplayed()
        screenshot("cards-light-large-text")
        compose.runOnIdle { dark = true }
        screenshot("cards-dark-large-text")
    }

    @Test
    fun selectedLyricsShareOnlyThoseLines() {
        var shared: List<MusicLyric>? = null
        compose.setContent {
            MaterialTheme {
                Surface {
                    MusicLyricsPage(
                        playback,
                        manager,
                        { _, lines -> shared = lines },
                        Modifier.width(360.dp).fillMaxHeight().padding(16.dp),
                    )
                }
            }
        }
        screenshot("lyrics-light")
        compose.onNodeWithText("选几句给 TA 看").performClick()
        compose.onNodeWithText("这是第 4 句测试歌词").performClick()
        compose.onNodeWithText("分享这 1 句").performClick()
        compose.runOnIdle { assertEquals(listOf(song.lyrics[3]), shared) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Gradle uninstalls the app after instrumentation; public test images survive that cleanup.
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WatermelonMusicTests")
            }
        val resolver = context.contentResolver
        val uri =
            requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { output ->
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
