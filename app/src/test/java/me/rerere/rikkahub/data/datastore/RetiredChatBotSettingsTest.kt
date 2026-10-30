package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetiredChatBotSettingsTest {
    @Test
    fun `upgrade erases both channel credentials while preserving unrelated preferences`() {
        val qq = stringPreferencesKey("qq_bot_setting")
        val wechat = stringPreferencesKey("wechat_bot_setting")
        val providers = stringPreferencesKey("providers")
        val proactive = stringPreferencesKey("proactive_message_setting")
        val keepAlive = booleanPreferencesKey("keep_alive_enabled")
        for (retiredValue in
            listOf("legacy-plaintext-credentials", "opaque-encrypted-credentials")) {
            val preferences =
                mutablePreferencesOf(
                    qq to retiredValue,
                    wechat to retiredValue,
                    providers to "unchanged-provider-settings",
                    proactive to "unchanged-proactive-settings",
                    keepAlive to true,
                )

            preferences.removeRetiredChatBotSettings()
            preferences.removeRetiredChatBotSettings() // Reopening after upgrade must be harmless.

            assertNull(preferences[qq])
            assertNull(preferences[wechat])
            assertEquals("unchanged-provider-settings", preferences[providers])
            assertEquals("unchanged-proactive-settings", preferences[proactive])
            assertEquals(true, preferences[keepAlive])
            assertEquals(3, preferences.asMap().size)
        }
    }
}
