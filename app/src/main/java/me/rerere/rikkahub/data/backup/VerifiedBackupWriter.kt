package me.rerere.rikkahub.data.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Shared stream protocol, independent of Android file providers so failures can be regression tested. */
object VerifiedBackupWriter {
    fun validateArchive(file: File, checkActive: () -> Unit = {}) {
        ZipFile(file).use { zip ->
            check(zip.getEntry("settings.json") != null && zip.getEntry("rikka_hub.db") != null) {
                "备份缺少设置或数据库"
            }
            val entries = zip.entries()
            val buffer = ByteArray(64 * 1024)
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val crc = CRC32()
                var size = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        crc.update(buffer, 0, count)
                        size += count
                    }
                }
                check(size == entry.size && crc.value == entry.crc) { "备份压缩包校验失败" }
            }
        }
    }

    fun copyAndVerify(
        file: File, output: () -> OutputStream, readBack: () -> InputStream, checkActive: () -> Unit = {},
    ) {
        output().use { out ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
            }
        }
        val original = file.inputStream().use { digest(it, checkActive) }
        val copied = readBack().use { digest(it, checkActive) }
        check(original.contentEquals(copied)) { "备份写入校验失败，保留原备份" }
    }

    private fun digest(input: InputStream, checkActive: () -> Unit): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }
}
