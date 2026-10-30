package me.rerere.rikkahub.service

import java.io.File
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeAudio
import me.rerere.ai.util.toGoogleAudio
import me.rerere.rikkahub.data.audio.pcmWavHeader
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

class RawVoiceCallMessageTest {
    @Test
    fun `native call audio reaches Google as original WAV bytes without a transcript`() {
        val file = File.createTempFile("voice-call-test", ".wav")
        try {
            val bytes = pcmWavHeader(640) + ByteArray(640) { (it % 127).toByte() }
            file.writeBytes(bytes)
            val parts = rawVoiceCallParts(file.toURI().toString())
            val audio = parts.filterIsInstance<UIMessagePart.Audio>().single()
            val inline = audio.encodeAudio().toGoogleAudio()["inlineData"]!!.jsonObject
            assertEquals("audio/wav", inline["mimeType"]!!.jsonPrimitive.content)
            assertArrayEquals(
                bytes,
                Base64.getDecoder().decode(inline["data"]!!.jsonPrimitive.content),
            )
            assertTrue(parts.none { it is UIMessagePart.VoiceMessage })
        } finally {
            file.delete()
        }
    }

    @Test
    fun `remote URLs cannot be silently substituted for local microphone recordings`() {
        assertTrue(runCatching { rawVoiceCallParts("https://example.com/audio.wav") }.isFailure)
    }

    @Test
    fun `old settings keep text recognition and native input preserves model and TTS choices`() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString<Settings>("{}")
        assertFalse(old.voiceCallRawAudio)
        assertTrue(old.voiceCallListenWhileReplying)
        val native = old.copy(voiceCallRawAudio = true, voiceCallListenWhileReplying = false)
        val decoded = Json.decodeFromString<Settings>(Json.encodeToString(native))
        assertTrue(decoded.voiceCallRawAudio)
        assertFalse(decoded.voiceCallListenWhileReplying)
        assertEquals(old.chatModelId, decoded.chatModelId)
        assertEquals(old.selectedTTSProviderId, decoded.selectedTTSProviderId)
        assertEquals(old.selectedASRProviderId, decoded.selectedASRProviderId)
    }
}
