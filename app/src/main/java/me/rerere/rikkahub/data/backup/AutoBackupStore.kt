package me.rerere.rikkahub.data.backup

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Device-local: a restored ZIP must never enable scheduling with another phone's folder grants.
private val Context.autoBackupPreferences by preferencesDataStore("auto_backup")

class AutoBackupStore(context: Context) {
    private val data = context.applicationContext.autoBackupPreferences
    val states = data.data.map { read(it) }
    suspend fun current() = states.first()

    suspend fun configure(edit: (AutoBackupConfig) -> AutoBackupConfig) {
        data.edit { prefs ->
            val before = read(prefs).config
            val next = edit(before).copy(revision = before.revision + 1)
            next.validate()
            prefs[enabled] = next.enabled
            prefs[folder] = next.folder
            prefs[days] = next.intervalDays
            prefs[hour] = next.hour
            prefs[minute] = next.minute
            prefs[revision] = next.revision
            prefs[error] = ""
            if (before.folder != next.folder) {
                // A new destination must not be blocked by a lost grant for the previous folder.
                // Leave that folder's files intact; only manage files created in the new destination.
                prefs[lastSuccess] = 0
                prefs.remove(latest)
                prefs.remove(pending)
                prefs.remove(retired)
            }
        }
    }

    suspend fun pending(uri: String) { data.edit { it[pending] = uri } }
    suspend fun clearPending() { data.edit { it.remove(pending) } }
    suspend fun error(message: String) { data.edit { it[error] = message } }
    suspend fun retiredRemoved(uri: String) { data.edit { it[retired] = it[retired].orEmpty() - uri } }

    suspend fun publish(uri: String, expectedRevision: Long, time: Long) {
        data.edit {
            val next = read(it).withPublishedBackup(uri, expectedRevision, time)
            it[retired] = next.retiredFiles
            it[latest] = next.latestFile
            it[lastSuccess] = next.lastSuccess
            it.remove(pending)
            it[error] = ""
        }
    }

    private fun read(p: Preferences) = AutoBackupState(
        config = AutoBackupConfig(
            p[enabled] ?: false, p[folder].orEmpty(), p[days] ?: 1,
            p[hour] ?: 3, p[minute] ?: 0, p[revision] ?: 0,
        ),
        lastSuccess = p[lastSuccess] ?: 0,
        latestFile = p[latest].orEmpty(), pendingFile = p[pending].orEmpty(),
        retiredFiles = p[retired].orEmpty(), error = p[error].orEmpty(),
    )

    private companion object {
        val enabled = booleanPreferencesKey("enabled")
        val folder = stringPreferencesKey("folder")
        val days = intPreferencesKey("days")
        val hour = intPreferencesKey("hour")
        val minute = intPreferencesKey("minute")
        val revision = longPreferencesKey("revision")
        val lastSuccess = longPreferencesKey("last_success")
        val latest = stringPreferencesKey("latest")
        val pending = stringPreferencesKey("pending")
        val retired = stringSetPreferencesKey("retired")
        val error = stringPreferencesKey("error")
    }
}
