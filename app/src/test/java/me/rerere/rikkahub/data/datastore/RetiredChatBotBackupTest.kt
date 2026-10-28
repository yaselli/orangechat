package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredChatBotBackupTest {
    @Test
    fun `old backup fields are ignored and never exported again`() {
        val settings =
            JsonInstant.decodeFromString<Settings>(
                """{
            "webServerPort":19090,
            "keepAliveEnabled":true,
            "qqBotSetting":{"enabled":true,"appSecret":"fake-qq-secret"},
            "wechatBotSetting":{"enabled":true,"botToken":"fake-wechat-token"}
        }"""
            )
        assertEquals(19090, settings.webServerPort)
        assertTrue(settings.keepAliveEnabled)

        val exported = JsonInstant.encodeToString(settings)
        for (removed in
            listOf("qqBotSetting", "wechatBotSetting", "fake-qq-secret", "fake-wechat-token")) {
            assertFalse(exported.contains(removed))
        }
    }
}
