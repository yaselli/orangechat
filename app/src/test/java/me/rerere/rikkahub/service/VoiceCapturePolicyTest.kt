package me.rerere.rikkahub.service

import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import org.junit.Assert.*
import org.junit.Test

class VoiceCapturePolicyTest {
    @Test fun muteAndCallLifecycleAlwaysTakePriorityOverContinuousListening() {
        for (status in VoiceCallStatus.entries) {
            assertFalse(shouldCaptureVoice(status, true, true))
            assertFalse(shouldCaptureVoice(status, true, false))
        }
        for (status in listOf(VoiceCallStatus.Idle, VoiceCallStatus.Error)) {
            assertFalse(shouldCaptureVoice(status, false, true))
            assertFalse(shouldCaptureVoice(status, false, false))
        }
    }
    @Test fun replyCaptureCanBeDisabledWithoutPreventingTheNextUserTurn() {
        for (status in listOf(VoiceCallStatus.Processing, VoiceCallStatus.Speaking)) {
            assertFalse(shouldCaptureVoice(status, false, false))
            assertTrue(shouldCaptureVoice(status, false, true))
        }
        assertTrue(shouldCaptureVoice(VoiceCallStatus.Listening, false, false))
        assertTrue(shouldCaptureVoice(VoiceCallStatus.Listening, false, true))
    }
}
