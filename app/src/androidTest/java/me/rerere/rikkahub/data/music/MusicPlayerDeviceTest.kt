package me.rerere.rikkahub.data.music

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import me.rerere.rikkahub.plugin.webview.MusicPlayerService
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real local audio, real service lifecycle. No NetEase account, network or model calls. */
@RunWith(AndroidJUnit4::class)
class MusicPlayerDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private val files = mutableListOf<File>()

    @After
    fun cleanUp() {
        activity.scenario.moveToState(Lifecycle.State.RESUMED)
        main { MusicPlayerService.stop(context) }
        await { !foreground() && MusicPlayerService.getNowPlaying()["filePath"].isNullOrEmpty() }
        files.forEach { it.delete() }
    }

    @Test
    fun backgroundQueueKeepsForegroundAcrossTracksAndNotificationStopEndsIt() {
        val first = wave(1)
        val second = wave(5)
        main { MusicPlayerService.play(context, first.path, "first", "test", keepForQueue = true) }
        await { main { MusicPlayerService.playbackSnapshot().source == first.path } }
        activity.scenario.moveToState(Lifecycle.State.CREATED)
        await { MusicPlayerService.isWaitingForQueue() }
        assertTrue("queue handoff must keep the existing foreground service", foreground())
        main {
            MusicPlayerService.play(context, second.path, "second", "test", keepForQueue = true)
        }
        await {
            main {
                MusicPlayerService.playbackSnapshot().let {
                    it.source == second.path && it.state == "playing"
                }
            }
        }
        assertTrue(foreground())
        val stopped = AtomicInteger()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    stopped.incrementAndGet()
                }
            }
        val broadcasts = LocalBroadcastManager.getInstance(context)
        broadcasts.registerReceiver(receiver, IntentFilter(MusicPlayerService.ACTION_MUSIC_STOPPED))
        try {
            main { MusicPlayerService.stop(context) }
            await { stopped.get() > 0 && !foreground() }
            assertFalse(MusicPlayerService.isWaitingForQueue())
            assertEquals("stopped", MusicPlayerService.getState())
        } finally {
            broadcasts.unregisterReceiver(receiver)
        }
    }

    @Test
    fun ordinaryLocalPluginPlaybackDoesNotHoldTheForegroundServiceAfterCompletion() {
        val file = wave(1)
        main { MusicPlayerService.play(context, file.path, "plugin", "test") }
        await { main { MusicPlayerService.playbackSnapshot().source == file.path } }
        await { MusicPlayerService.getState() == "stopped" && !foreground() }
        assertFalse(MusicPlayerService.isWaitingForQueue())
    }

    @Test
    fun failedPlaybackReleasesThePlayerAndItsForegroundState() {
        val missing = File(context.cacheDir, "music-missing-${System.nanoTime()}.wav")
        main {
            MusicPlayerService.play(context, missing.path, "missing", "test", keepForQueue = true)
        }
        await {
            main {
                MusicPlayerService.playbackSnapshot().let {
                    it.source == missing.path && it.state == "stopped" && it.durationMs == 0L
                }
            } && !foreground()
        }
        assertFalse(MusicPlayerService.isWaitingForQueue())
    }

    private fun wave(seconds: Int): File {
        val dataSize = 8000 * 2 * seconds
        val data =
            ByteBuffer.allocate(44 + dataSize)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray())
                    putInt(36 + dataSize)
                    put("WAVEfmt ".toByteArray())
                    putInt(16)
                    putShort(1)
                    putShort(1)
                    putInt(8000)
                    putInt(16000)
                    putShort(2)
                    putShort(16)
                    put("data".toByteArray())
                    putInt(dataSize)
                }
                .array()
        return File(context.cacheDir, "music-test-${System.nanoTime()}.wav").also {
            it.writeBytes(data)
            files += it
        }
    }

    @Suppress("DEPRECATION")
    private fun foreground(): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(100)
            .any { it.service.className == MusicPlayerService::class.java.name && it.foreground }

    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }

    private fun await(condition: () -> Boolean) {
        val limit = SystemClock.uptimeMillis() + 15000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < limit) { "Playback condition did not settle" }
            SystemClock.sleep(100)
        }
    }
}
