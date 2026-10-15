package me.rerere.ai.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.provider.ProviderSetting
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64

private const val MAX_AUDIO_BYTES = 12L * 1024 * 1024

data class EncodedAudio(val data: String, val mimeType: String, val format: String)

/** Transport support only; the selected model and upstream server must also accept audio. */
fun supportsRawAudioInput(provider: ProviderSetting?): Boolean =
    provider is ProviderSetting.Google || (provider is ProviderSetting.OpenAI && !provider.useResponseApi)

/** Read original bytes: never transcribe, rename a codec, or silently discard an unreadable recording. */
fun UIMessagePart.Audio.encodeAudio(): EncodedAudio {
    require(url.startsWith("file:")) { "请先将音频保存为本地附件" }
    return encodeAudioFile(File(URI(url)))
}

internal fun encodeAudioFile(file: File): EncodedAudio {
    require(file.isFile) { "录音文件不存在，请重新添加音频" }
    require(file.length() in 12..MAX_AUDIO_BYTES) { "音频为空或超过 12 MB，请缩短录音" }
    val header = ByteArray(16)
    file.inputStream().use { it.read(header) }
    val prefix = header.toString(Charsets.ISO_8859_1)
    val (mime, format) = when {
        prefix.startsWith("RIFF") && prefix.substring(8, 12) == "WAVE" -> "audio/wav" to "wav"
        prefix.startsWith("ID3") ||
            (header[0].toInt() and 0xff == 0xff && header[1].toInt() and 0xe0 == 0xe0 &&
                header[1].toInt() and 0x06 != 0) -> "audio/mpeg" to "mp3"
        prefix.startsWith("fLaC") -> "audio/flac" to "flac"
        prefix.startsWith("OggS") -> "audio/ogg" to "ogg"
        prefix.substring(4, 8) == "ftyp" -> "audio/mp4" to "m4a"
        else -> error("无法识别音频格式，请使用 WAV 或 MP3")
    }
    val bytes = file.inputStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count = input.read(buffer)
        while (count >= 0) {
            require(output.size().toLong() + count <= MAX_AUDIO_BYTES) { "音频超过 12 MB，请缩短录音" }
            output.write(buffer, 0, count)
            count = input.read(buffer)
        }
        output.toByteArray()
    }
    return EncodedAudio(Base64.getEncoder().encodeToString(bytes), mime, format)
}

fun EncodedAudio.toChatCompletionsAudio(): JsonObject {
    require(format == "wav" || format == "mp3") { "OpenAI 兼容音频输入请使用 WAV 或 MP3" }
    return buildJsonObject {
        put("type", "input_audio")
        put("input_audio", buildJsonObject {
            put("data", data)
            put("format", format)
        })
    }
}

fun EncodedAudio.toGoogleAudio(): JsonObject = buildJsonObject {
    put("inlineData", buildJsonObject {
        put("mimeType", mimeType)
        put("data", data)
    })
}
