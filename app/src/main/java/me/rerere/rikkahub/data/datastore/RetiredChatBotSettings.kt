package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey

/** Erase the retired channels' plaintext or encrypted values without decrypting or reading them. */
internal fun MutablePreferences.removeRetiredChatBotSettings() {
    remove(stringPreferencesKey("qq_bot_setting"))
    remove(stringPreferencesKey("wechat_bot_setting"))
}
