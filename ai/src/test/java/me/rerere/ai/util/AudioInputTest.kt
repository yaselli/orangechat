package me.rerere.ai.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.util.Base64

class AudioInputTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun wavBytes() = ByteArray(80).apply {
        "RIFF".toByteArray().copyInto(this)
        "WAVE".toByteArray().copyInto(this, 8)
        this[60] = 23 // The original audio bytes must survive encoding unchanged.
    }

    @Test
    fun `wav encoding uses original bytes and actual container even with misleading extension`() {
        val bytes = wavBytes()
        val file = temporary.newFile("recording with spaces.mp3").apply { writeBytes(bytes) }
        val encoded = UIMessagePart.Audio(file.toURI().toString()).encodeAudio()
        assertEquals("wav", encoded.format)
        assertEquals("audio/wav", encoded.mimeType)
        assertArrayEquals(bytes, Base64.getDecoder().decode(encoded.data))
    }

    @Test
    fun `chat completions audio is raw base64 with format and no transcript`() {
        val result = EncodedAudio("AQID", "audio/wav", "wav").toChatCompletionsAudio()
        assertEquals("input_audio", result["type"]!!.jsonPrimitive.content)
        assertEquals("AQID", result["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("wav", result["input_audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertFalse(result.toString().contains("transcript"))
    }

    @Test
    fun `google inline audio carries its actual mime and plain base64`() {
        val inline = EncodedAudio("AQID", "audio/wav", "wav").toGoogleAudio()["inlineData"]!!.jsonObject
        assertEquals("audio/wav", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals("AQID", inline["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mp3 is identified by its header`() {
        val file = temporary.newFile("audio.bin").apply { writeBytes("ID3".toByteArray() + ByteArray(30)) }
        assertEquals("mp3", encodeAudioFile(file).format)
        assertEquals("audio/mpeg", encodeAudioFile(file).mimeType)
    }

    @Test
    fun `unsupported openai codec fails instead of being labelled mp3`() {
        val file = temporary.newFile("audio.flac").apply { writeBytes("fLaC".toByteArray() + ByteArray(30)) }
        val encoded = encodeAudioFile(file)
        assertEquals("audio/flac", encoded.mimeType)
        try {
            encoded.toChatCompletionsAudio()
            fail("Unsupported codec must fail")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `missing empty unknown and oversized files fail before uploading`() {
        val missing = temporary.root.resolve("missing.wav")
        val empty = temporary.newFile("empty.wav")
        val unknown = temporary.newFile("unknown.wav").apply { writeBytes(ByteArray(30)) }
        val large = temporary.newFile("large.wav").apply {
            RandomAccessFile(this, "rw").use { it.setLength(13L * 1024 * 1024) }
        }
        for (file in listOf(missing, empty, unknown, large)) {
            try {
                encodeAudioFile(file)
                fail("Invalid audio must fail: ${file.name}")
            } catch (_: IllegalStateException) {
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun `complete user message serialization retains audio alongside text`() {
        val bytes = wavBytes()
        val file = temporary.newFile("recording.wav").apply { writeBytes(bytes) }
        val message = UIMessage.user("listen").copy(parts = listOf(
            UIMessagePart.Text("listen"), UIMessagePart.Audio(file.toURI().toString()),
        ))
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildMessages", List::class.java, Model::class.java
        )
        method.isAccessible = true
        val messages = method.invoke(api, listOf(message), Model()) as JsonArray
        val content = messages.single().jsonObject["content"]!!.jsonArray
        assertEquals("listen", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        val audio = content[1].jsonObject["input_audio"]!!.jsonObject
        assertArrayEquals(bytes, Base64.getDecoder().decode(audio["data"]!!.jsonPrimitive.content))
        assertEquals("wav", audio["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `audio-only user message is retained in the actual request`() {
        val bytes = wavBytes()
        val file = temporary.newFile("audio-only.wav").apply { writeBytes(bytes) }
        val message = UIMessage.user("").copy(parts = listOf(UIMessagePart.Audio(file.toURI().toString())))
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildMessages", List::class.java, Model::class.java
        )
        method.isAccessible = true
        val messages = method.invoke(api, listOf(message), Model()) as JsonArray
        val content = messages.single().jsonObject["content"]!!.jsonArray
        val audio = content.single().jsonObject["input_audio"]!!.jsonObject
        assertArrayEquals(bytes, Base64.getDecoder().decode(audio["data"]!!.jsonPrimitive.content))
    }

    @Test
    fun `responses rejects audio explicitly rather than quietly dropping it`() {
        val message = UIMessage.user("").copy(parts = listOf(UIMessagePart.Audio("file:///recording.wav")))
        try {
            ResponseAPI(OkHttpClient()).buildMessages(listOf(message))
            fail("Unsupported API must fail")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("Chat Completions"))
        }
    }

    @Test
    fun `transport capability does not depend on configured asr`() {
        assertTrue(supportsRawAudioInput(ProviderSetting.Google()))
        assertTrue(supportsRawAudioInput(ProviderSetting.OpenAI()))
        assertFalse(supportsRawAudioInput(ProviderSetting.OpenAI(useResponseApi = true)))
        assertFalse(supportsRawAudioInput(ProviderSetting.Claude()))
        assertFalse(supportsRawAudioInput(null))
    }
}
