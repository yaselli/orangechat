package me.rerere.rikkahub.data.backup

import java.time.Instant
import java.time.ZoneId

val AUTO_BACKUP_INTERVALS = listOf(1, 3, 7, 15)

data class AutoBackupConfig(
    val enabled: Boolean = false,
    val folder: String = "",
    val intervalDays: Int = 1,
    val hour: Int = 3,
    val minute: Int = 0,
    val revision: Long = 0,
) {
    fun validate() {
        require(intervalDays in AUTO_BACKUP_INTERVALS)
        require(hour in 0..23 && minute in 0..59)
        require(!enabled || folder.isNotBlank()) { "请先选择备份文件夹" }
    }
}

data class AutoBackupState(
    val config: AutoBackupConfig = AutoBackupConfig(),
    val lastSuccess: Long = 0,
    val latestFile: String = "",
    val pendingFile: String = "",
    val retiredFiles: Set<String> = emptySet(),
    val error: String = "",
)

/** Calendar days in the phone's timezone, not a fixed 24h interval across daylight-saving changes. */
fun nextAutoBackupAt(now: Instant, lastSuccess: Long, config: AutoBackupConfig, zone: ZoneId): Instant {
    config.validate()
    val local = now.atZone(zone)
    val day = if (lastSuccess > 0) Instant.ofEpochMilli(lastSuccess).atZone(zone).toLocalDate()
        .plusDays(config.intervalDays.toLong()) else local.toLocalDate()
    val next = day.atTime(config.hour, config.minute).atZone(zone).toInstant()
    return if (lastSuccess == 0L && !next.isAfter(now)) day.plusDays(1)
        .atTime(config.hour, config.minute).atZone(zone).toInstant() else next
}

/** The old file only becomes eligible for cleanup after a verified candidate is durably published. */
internal fun AutoBackupState.withPublishedBackup(uri: String, expectedRevision: Long, time: Long): AutoBackupState {
    check(config.revision == expectedRevision) { "备份设置已变更，请重新备份" }
    check(uri.isNotBlank() && pendingFile == uri) { "备份临时记录不匹配" }
    return copy(
        latestFile = uri, pendingFile = "", lastSuccess = time, error = "",
        retiredFiles = if (latestFile.isBlank() || latestFile == uri) retiredFiles else retiredFiles + latestFile,
    )
}
