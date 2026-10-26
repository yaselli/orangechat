package me.rerere.rikkahub.data.music

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.security.SecretCrypto

/** Kept out of Android/cloud backups; a restored copy must scan its own NetEase login again. */
class SharedMusicCredentials(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "shared-music.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private val aad = "shared-music-connection-v1"
    private var cached: MusicConnection? = runCatching {
        val encrypted = file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        json.decodeFromString<MusicConnection>(requireNotNull(SecretCrypto.decrypt(encrypted, aad)))
    }
        .getOrNull()

    @Synchronized fun get(): MusicConnection? = cached

    @Synchronized
    fun save(value: MusicConnection?) {
        if (value == null) {
            file.delete()
            cached = null
            return
        }
        val encrypted =
            requireNotNull(
                SecretCrypto.encrypt(json.encodeToString(MusicConnection.serializer(), value), aad)
            )
        val output = file.startWrite()
        try {
            output.write(encrypted.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            cached = value
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }
}
