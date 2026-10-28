package me.rerere.rikkahub.data.audio

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real microphone lifecycle without a model, ASR, TTS or network connection. */
@RunWith(AndroidJUnit4::class)
class RawVoiceCallRecorderDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test
    fun cancellationReleasesMicrophoneAndTheNextCallCanRecord() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        repeat(2) {
            val captured = CompletableDeferred<Unit>()
            val job = launch {
                recordVoiceCallAudio(canCapture = { true }, captureEpoch = { 0L }) { _, _ ->
                    captured.complete(Unit)
                }
            }
            try {
                withTimeout(10_000) { captured.await() }
            } finally {
                withTimeout(5_000) { job.cancelAndJoin() }
            }
            assertFalse(job.isActive)
        }
    }

    @Test
    fun mutedCallDoesNotProduceOrForwardAnyAudioFrames() = runBlocking {
        val running = AtomicBoolean(false)
        val captured = CompletableDeferred<Unit>()
        val job = launch {
            recordVoiceCallAudio(canCapture = running::get, captureEpoch = { 0L }) { _, _ ->
                captured.complete(Unit)
            }
        }
        kotlinx.coroutines.delay(250)
        job.cancelAndJoin()
        assertFalse(captured.isCompleted)
    }
}
