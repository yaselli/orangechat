/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import org.json.JSONObject

enum class JealousyMood {
    CALM,
    CONCERNED,
    JEALOUS,
    ANGRY,
    RECONCILING,
    FORCED_OPEN,
}

data class JealousyInspectionState(
    val enabled: Boolean,
    val score: Int,
    val inspectionCount: Int,
    val silenceStartedAt: Long,
    val lastInspectionAt: Long,
    val managedPackages: Set<String>,
    val whitelistPackages: Set<String>,
    val jealousyLockedPackages: Set<String>,
    val recentUsageMinutes: Map<String, Int>,
    val lastTriggeredStage: Int,
    val reconciling: Boolean,
    val forcedOpen: Boolean,
) {
    val mood: JealousyMood
        get() = when {
            forcedOpen -> JealousyMood.FORCED_OPEN
            reconciling -> JealousyMood.RECONCILING
            score >= MAX_SCORE -> JealousyMood.ANGRY
            score >= LOCK_THRESHOLD -> JealousyMood.JEALOUS
            score >= CONCERNED_THRESHOLD -> JealousyMood.CONCERNED
            else -> JealousyMood.CALM
        }

    companion object {
        const val CONCERNED_THRESHOLD = 30
        const val LOCK_THRESHOLD = 70
        const val MAX_SCORE = 100
    }
}

object JealousyInspectionStore {
    const val START_DELAY_MINUTES = 30
    const val INSPECTION_INTERVAL_MINUTES = 15
    const val CONTINUOUS_BONUS_MINUTES = 15
    const val CONTINUOUS_BONUS_SCORE = 10

    private const val PREFS = "jealousy_inspection"
    private const val ENABLED = "enabled"
    private const val SCORE = "score"
    private const val INSPECTION_COUNT = "inspection_count"
    private const val SILENCE_STARTED_AT = "silence_started_at"
    private const val LAST_INSPECTION_AT = "last_inspection_at"
    private const val MANAGED_PACKAGES = "managed_packages"
    private const val WHITELIST_PACKAGES = "whitelist_packages"
    private const val JEALOUSY_LOCKED_PACKAGES = "jealousy_locked_packages"
    private const val RECENT_USAGE = "recent_usage"
    private const val LAST_TRIGGERED_STAGE = "last_triggered_stage"
    private const val RECONCILING = "reconciling"
    private const val FORCED_OPEN = "forced_open"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE,
    )

    fun read(context: Context): JealousyInspectionState {
        val values = prefs(context)
        return JealousyInspectionState(
            enabled = values.getBoolean(ENABLED, false),
            score = values.getInt(SCORE, 0).coerceIn(0, JealousyInspectionState.MAX_SCORE),
            inspectionCount = values.getInt(INSPECTION_COUNT, 0).coerceAtLeast(0),
            silenceStartedAt = values.getLong(SILENCE_STARTED_AT, 0L),
            lastInspectionAt = values.getLong(LAST_INSPECTION_AT, 0L),
            managedPackages = values.getStringSet(MANAGED_PACKAGES, emptySet())?.toSet().orEmpty(),
            whitelistPackages = values.getStringSet(WHITELIST_PACKAGES, emptySet())?.toSet().orEmpty(),
            jealousyLockedPackages = values.getStringSet(JEALOUSY_LOCKED_PACKAGES, emptySet())
                ?.toSet().orEmpty(),
            recentUsageMinutes = decodeUsage(values.getString(RECENT_USAGE, null)),
            lastTriggeredStage = values.getInt(LAST_TRIGGERED_STAGE, 0),
            reconciling = values.getBoolean(RECONCILING, false),
            forcedOpen = values.getBoolean(FORCED_OPEN, false),
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply()
        if (enabled) {
            startSilenceCycle(context)
            JealousyInspectionWorker.schedule(context, START_DELAY_MINUTES)
        } else {
            JealousyInspectionWorker.cancel(context)
        }
    }

    fun setManagedPackages(context: Context, packages: Set<String>) {
        val allowed = packages - protectedPackages(context)
        prefs(context).edit().putStringSet(MANAGED_PACKAGES, allowed).apply()
    }

    fun setWhitelistPackages(context: Context, packages: Set<String>) {
        val whitelist = packages + protectedPackages(context)
        val managed = read(context).managedPackages - whitelist
        prefs(context).edit()
            .putStringSet(WHITELIST_PACKAGES, whitelist)
            .putStringSet(MANAGED_PACKAGES, managed)
            .apply()
    }

    fun startSilenceCycle(context: Context, startedAt: Long = System.currentTimeMillis()) {
        val state = read(context)
        if (!state.enabled || state.jealousyLockedPackages.isNotEmpty() || state.reconciling || state.forcedOpen) return
        prefs(context).edit()
            .putLong(SILENCE_STARTED_AT, startedAt)
            .putLong(LAST_INSPECTION_AT, 0L)
            .putInt(INSPECTION_COUNT, 0)
            .putInt(LAST_TRIGGERED_STAGE, 0)
            .putBoolean(FORCED_OPEN, false)
            .apply()
    }

    fun recordInspection(context: Context, score: Int, usageMinutes: Map<String, Int>) {
        val state = read(context)
        prefs(context).edit()
            .putInt(SCORE, score.coerceIn(state.score, JealousyInspectionState.MAX_SCORE))
            .putInt(INSPECTION_COUNT, state.inspectionCount + 1)
            .putLong(LAST_INSPECTION_AT, System.currentTimeMillis())
            .putString(RECENT_USAGE, JSONObject(usageMinutes).toString())
            .apply()
    }

    fun recordTriggeredStage(context: Context, stage: Int) {
        prefs(context).edit().putInt(LAST_TRIGGERED_STAGE, stage).apply()
    }

    fun stageForScore(score: Int): Int = when {
        score >= JealousyInspectionState.MAX_SCORE -> 100
        score >= JealousyInspectionState.LOCK_THRESHOLD -> 70
        score >= JealousyInspectionState.CONCERNED_THRESHOLD -> 30
        else -> 0
    }

    fun recordUserReturn(context: Context) {
        val state = read(context)
        if (state.jealousyLockedPackages.isNotEmpty() || state.reconciling || state.forcedOpen) return
        prefs(context).edit()
            .putInt(SCORE, 0)
            .putLong(SILENCE_STARTED_AT, 0L)
            .putLong(LAST_INSPECTION_AT, 0L)
            .putString(RECENT_USAGE, null)
            .putInt(LAST_TRIGGERED_STAGE, 0)
            .apply()
    }

    fun recordJealousyLocks(context: Context, packages: Set<String>) {
        val state = read(context)
        val allowed = packages.intersect(state.managedPackages) - effectiveWhitelist(context)
        prefs(context).edit()
            .putStringSet(JEALOUSY_LOCKED_PACKAGES, state.jealousyLockedPackages + allowed)
            .putBoolean(RECONCILING, false)
            .putBoolean(FORCED_OPEN, false)
            .apply()
    }

    fun beginReconciliation(context: Context) {
        prefs(context).edit()
            .putInt(SCORE, 20)
            .putBoolean(RECONCILING, true)
            .putBoolean(FORCED_OPEN, false)
            .apply()
    }

    fun completeReconciliation(context: Context) {
        prefs(context).edit()
            .putInt(SCORE, 0)
            .putBoolean(RECONCILING, false)
            .putBoolean(FORCED_OPEN, false)
            .putStringSet(JEALOUSY_LOCKED_PACKAGES, emptySet())
            .putLong(SILENCE_STARTED_AT, 0L)
            .apply()
    }

    fun recordForcedOpen(context: Context) {
        prefs(context).edit()
            .putBoolean(FORCED_OPEN, true)
            .putBoolean(RECONCILING, false)
            .putLong(SILENCE_STARTED_AT, 0L)
            .apply()
    }

    fun resetAll(context: Context) {
        val state = read(context)
        prefs(context).edit().clear()
            .putBoolean(ENABLED, state.enabled)
            .putStringSet(MANAGED_PACKAGES, state.managedPackages)
            .putStringSet(WHITELIST_PACKAGES, state.whitelistPackages)
            .apply()
    }

    fun effectiveWhitelist(context: Context): Set<String> =
        read(context).whitelistPackages + protectedPackages(context)

    fun protectedPackages(context: Context): Set<String> = setOf(
        context.packageName,
        "com.android.settings",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.dialer",
        "com.android.dialer",
        "com.android.phone",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
    )

    private fun decodeUsage(value: String?): Map<String, Int> {
        if (value.isNullOrBlank()) return emptyMap()
        return runCatching {
            val objectValue = JSONObject(value)
            objectValue.keys().asSequence().associateWith { packageName ->
                objectValue.optInt(packageName, 0).coerceAtLeast(0)
            }
        }.getOrDefault(emptyMap())
    }
}
