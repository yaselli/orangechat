package me.rerere.rikkahub.data.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AutoBackupTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun `first schedule uses upcoming local 3am and never runs immediately on enable`() {
        val config = AutoBackupConfig()
        assertEquals(Instant.parse("2026-10-16T19:00:00Z"),
            nextAutoBackupAt(Instant.parse("2026-10-16T14:59:00Z"), 0, config, shanghai))
        assertEquals(Instant.parse("2026-10-17T19:00:00Z"),
            nextAutoBackupAt(Instant.parse("2026-10-16T19:00:00Z"), 0, config, shanghai))
    }

    @Test
    fun `interval follows last successful calendar date including manual backup`() {
        val success = Instant.parse("2026-10-16T14:59:00Z").toEpochMilli()
        for (days in AUTO_BACKUP_INTERVALS) {
            val config = AutoBackupConfig(intervalDays = days)
            val due = nextAutoBackupAt(Instant.ofEpochMilli(success), success, config, shanghai)
            assertEquals(16 + days, due.atZone(shanghai).dayOfMonth)
            assertEquals(3, due.atZone(shanghai).hour)
        }
        // A missed run remains due; opening the app must not push it another whole interval away.
        val due = nextAutoBackupAt(Instant.parse("2026-10-25T00:00:00Z"), success,
            AutoBackupConfig(intervalDays = 3), shanghai)
        assertTrue(due.isBefore(Instant.parse("2026-10-25T00:00:00Z")))
    }

    @Test
    fun `daylight saving uses local clock instead of fixed 24h elapsed time`() {
        val zone = ZoneId.of("America/New_York")
        val success = Instant.parse("2026-03-07T08:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"),
            nextAutoBackupAt(Instant.ofEpochMilli(success), success, AutoBackupConfig(), zone))
    }

    @Test
    fun `invalid intervals times or enabling without folder are rejected`() {
        for (config in listOf(AutoBackupConfig(intervalDays = 0), AutoBackupConfig(hour = 24),
            AutoBackupConfig(minute = 60), AutoBackupConfig(enabled = true))) {
            expectFailure { config.validate() }
        }
    }

    @Test
    fun `valid complete archive can be copied and read back byte for byte`() {
        val file = archive()
        try {
            VerifiedBackupWriter.validateArchive(file)
            val target = ByteArrayOutputStream()
            VerifiedBackupWriter.copyAndVerify(file, { target }, { ByteArrayInputStream(target.toByteArray()) })
            assertTrue(file.readBytes().contentEquals(target.toByteArray()))
        } finally { file.delete() }
    }

    @Test
    fun `provider truncation or modified readback fails verification`() {
        val file = archive()
        try {
            expectFailure {
                VerifiedBackupWriter.copyAndVerify(
                    file, { ByteArrayOutputStream() }, { ByteArrayInputStream(byteArrayOf(1)) },
                )
            }
        } finally { file.delete() }
    }

    @Test
    fun `write failures close streams and do not reach readback verification`() {
        val file = archive()
        var closed = false
        var read = false
        try {
            expectFailure {
                VerifiedBackupWriter.copyAndVerify(file, { object : OutputStream() {
                    override fun write(value: Int) { throw IOException("disk full") }
                    override fun close() { closed = true }
                } }, { read = true; ByteArrayInputStream(byteArrayOf()) })
            }
            assertTrue(closed)
            assertFalse(read)
        } finally { file.delete() }
    }

    @Test
    fun `cancelled jobs stop copying and cannot publish an unchecked file`() {
        val file = archive()
        var checked = false
        try {
            expectFailure {
                VerifiedBackupWriter.copyAndVerify(file, { ByteArrayOutputStream() }, {
                    checked = true; ByteArrayInputStream(byteArrayOf())
                }) { throw CancellationException() }
            }
            assertFalse(checked)
        } finally { file.delete() }
    }

    @Test
    fun `partial or incomplete zip is rejected before replacing the previous backup`() {
        val incomplete = archive(includeDatabase = false)
        val broken = Files.createTempFile("broken", ".zip").toFile().apply { writeText("not a ZIP") }
        try {
            expectFailure { VerifiedBackupWriter.validateArchive(incomplete) }
            expectFailure { VerifiedBackupWriter.validateArchive(broken) }
        } finally { incomplete.delete(); broken.delete() }
    }

    @Test
    fun `publishing retires only the owned previous backup and clears recovery pointer`() {
        val state = AutoBackupState(
            config = AutoBackupConfig(revision = 2), latestFile = "old.zip", pendingFile = "candidate.zip",
            lastSuccess = 100, error = "previous failure",
        )
        val next = state.withPublishedBackup("candidate.zip", 2, 200)
        assertEquals("candidate.zip", next.latestFile)
        assertEquals(setOf("old.zip"), next.retiredFiles)
        assertEquals("", next.pendingFile)
        assertEquals(200L, next.lastSuccess)
        assertEquals("", next.error)
        assertEquals("old.zip", state.latestFile)
    }

    @Test
    fun `changing config mid backup prevents publication and retains old success and file`() {
        val state = AutoBackupState(
            config = AutoBackupConfig(revision = 3), latestFile = "old.zip", pendingFile = "candidate.zip",
            lastSuccess = 100,
        )
        expectFailure { state.withPublishedBackup("candidate.zip", 2, 200) }
        expectFailure { state.withPublishedBackup("other.zip", 3, 200) }
        assertEquals("old.zip", state.latestFile)
        assertEquals(100L, state.lastSuccess)
        assertTrue(state.retiredFiles.isEmpty())
    }

    @Test
    fun `zip entry CRC corruption is caught even when central directory still opens`() {
        val file = archive()
        try {
            val data = file.readBytes()
            val central = (0 until data.size - 4).first { i ->
                data[i] == 0x50.toByte() && data[i + 1] == 0x4b.toByte() &&
                    data[i + 2] == 1.toByte() && data[i + 3] == 2.toByte()
            }
            data[central + 16] = (data[central + 16].toInt() xor 0xff).toByte()
            file.writeBytes(data)
            expectFailure { VerifiedBackupWriter.validateArchive(file) }
        } finally { file.delete() }
    }

    private fun archive(includeDatabase: Boolean = true): java.io.File {
        val file = Files.createTempFile("backup-test", ".zip").toFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            val names = listOf("settings.json", "upload/recording.aac", "plugins/test/main.js") +
                if (includeDatabase) listOf("rikka_hub.db", "rikka_hub-wal") else emptyList()
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("content for $name".toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun expectFailure(block: () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("Expected failure")
    }
}
