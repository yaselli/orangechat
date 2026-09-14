/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.capability

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import me.rerere.rikkahub.workflow.trigger.AppForegroundLastKnown
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 插件沙箱能力 - 应用使用统计桥接（appUsage.*）
 *
 * 通用设备能力，不含任何具体业务逻辑。
 * 调用方插件必须在 manifest.permissions 中声明 "device_apps"，
 * 由 [me.rerere.rikkahub.plugin.loader.PluginSandbox] 统一校验。
 *
 * 动作:
 * - today     {limit?}   今日使用统计（需要用户已授予"使用情况访问"权限）
 * - installed {}         可启动的应用列表（供插件做应用选择器）
 * - foreground {}        当前前台应用包名（基于无障碍事件流，可能为 null）
 */
object AppUsageBridge {
    private const val TAG = "AppUsageBridge"

    fun handle(context: Context, action: String, paramsJson: String): String {
        return try {
            val params = JSONObject(paramsJson.ifBlank { "{}" })
            when (action) {
                "today" -> today(context, params)
                "installed" -> installed(context)
                "foreground" -> foreground()
                "openSettings" -> openUsageAccessSettings(context)
                else -> error("unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "appUsage bridge error, action=$action", e)
            error(e.message ?: "Unknown error")
        }
    }

    /** 检查"使用情况访问"权限是否已授予 */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun today(context: Context, params: JSONObject): String {
        if (!hasUsageAccess(context)) {
            return error("no_usage_access").let {
                JSONObject(it).put("needs_permission", "usage_access").toString()
            }
        }
        val limit = params.optInt("limit", 10).coerceIn(1, 50)
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            cal.timeInMillis,
            System.currentTimeMillis(),
        ).orEmpty()
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(limit)

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val arr = JSONArray()
        stats.forEach { stat ->
            arr.put(JSONObject().apply {
                put("package", stat.packageName)
                put("app_name", appLabel(context, stat.packageName))
                put("usage_minutes", (stat.totalTimeInForeground / 60000).toInt())
                put("last_used", fmt.format(Date(stat.lastTimeUsed)))
            })
        }
        return ok().put("apps", arr).put("count", stats.size).toString()
    }

    private fun installed(context: Context): String {
        val pm = context.packageManager
        val self = context.packageName
        val arr = JSONArray()
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != self }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .distinctBy { it.packageName }
            .sortedBy { it.packageName }
            .forEach { info ->
                arr.put(JSONObject().apply {
                    put("package", info.packageName)
                    put("app_name", pm.getApplicationLabel(info).toString())
                    put("is_system", info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                })
            }
        return ok().put("apps", arr).toString()
    }

    private fun openUsageAccessSettings(context: Context): String {
        return try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ok().toString()
        } catch (e: Exception) {
            error(e.message ?: "cannot open settings")
        }
    }

    private fun foreground(): String {
        return ok()
            .put("package", AppForegroundLastKnown.value ?: JSONObject.NULL)
            .toString()
    }

    private fun appLabel(context: Context, pkg: String): String = try {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun ok() = JSONObject().put("success", true)
    private fun error(msg: String) = JSONObject().put("success", false).put("error", msg).toString()
}
