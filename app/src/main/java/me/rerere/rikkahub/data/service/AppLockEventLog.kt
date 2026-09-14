/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用锁事件日志
 *
 * 锁屏页上的用户动作（目前只有"强制解锁"）会记录到这里，
 * 插件通过 appLock.consumeEvents() 一次性取走并清空，
 * 用于让插件（以及 AI）知晓用户在锁屏页上做过什么。
 *
 * 独立于 AppLockStore，避免给锁定状态的热路径增加负担。
 */
object AppLockEventLog {
    private const val PREFS_NAME = "app_lock_event_log"
    private const val KEY_LOG = "events"
    private const val MAX_EVENTS = 100

    const val TYPE_FORCED_UNLOCK = "forced_unlock"

    @Synchronized
    fun record(context: Context, type: String, packageName: String, appLabel: String) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_LOG, "[]") ?: "[]")
        arr.put(JSONObject().apply {
            put("type", type)
            put("package", packageName)
            put("app_name", appLabel)
            put("timestamp", System.currentTimeMillis())
        })
        // 超限时丢弃最旧的记录
        while (arr.length() > MAX_EVENTS) arr.remove(0)
        prefs.edit().putString(KEY_LOG, arr.toString()).apply()
    }

    /** 取出全部事件并清空（消费语义） */
    @Synchronized
    fun consume(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val events = prefs.getString(KEY_LOG, "[]") ?: "[]"
        prefs.edit().putString(KEY_LOG, "[]").apply()
        return events
    }
}
