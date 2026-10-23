package me.rerere.rikkahub.service

import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus

internal fun shouldCaptureVoice(status: VoiceCallStatus, muted: Boolean, listenWhileReplying: Boolean): Boolean =
    !muted && when (status) {
        VoiceCallStatus.Listening -> true
        VoiceCallStatus.Processing, VoiceCallStatus.Speaking -> listenWhileReplying
        VoiceCallStatus.Idle, VoiceCallStatus.Error -> false
    }
