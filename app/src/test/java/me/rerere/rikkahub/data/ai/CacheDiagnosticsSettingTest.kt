package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheDiagnosticsSettingTest {
    @Test
    fun oldBackupsAndNewInstallsDoNotStartRequestDiagnostics() {
        assertFalse(DisplaySetting().cacheRequestDiagnosticsEnabled)
        assertFalse(Json.decodeFromString<DisplaySetting>("{}").cacheRequestDiagnosticsEnabled)
        assertFalse(
            Json.decodeFromString<DisplaySetting>("{\"showTokenUsage\":true}")
                .cacheRequestDiagnosticsEnabled
        )
    }

    @Test
    fun explicitOptInSurvivesBackupIndependentlyOfTokenDisplay() {
        val setting = DisplaySetting(cacheRequestDiagnosticsEnabled = true, showTokenUsage = false)
        val restored =
            Json.decodeFromString<DisplaySetting>(
                Json.encodeToString(DisplaySetting.serializer(), setting)
            )
        assertTrue(restored.cacheRequestDiagnosticsEnabled)
        assertFalse(restored.showTokenUsage)
        assertEquals(setting, restored)
    }
}
