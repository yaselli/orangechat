/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.capability

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import me.rerere.rikkahub.data.service.AppLockEventLog
import me.rerere.rikkahub.data.service.AppLockGuard
import me.rerere.rikkahub.data.service.AppLockStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件沙箱能力 - 应用锁桥接（appLock.*）
 *
 * 通用设备能力，不含任何具体业务逻辑。底层复用 AppLockStore / AppLockGuard。
 * 调用方插件必须在 manifest.permissions 中声明 "device_apps"，
 * 由 [me.rerere.rikkahub.plugin.loader.PluginSandbox] 统一校验。
 *
 * 动作:
 * - lock    {package, message?, requirePin?}  锁定应用（默认 AI 专属解锁模式）
 * - unlock  {package}                          解除锁定
 * - list    {}                                 当前锁定列表
 * - isLocked {package}                         查询单个应用是否被锁
 * - consumeEvents {}                           取出并清空锁事件日志（如强制解锁记录）
 */
object AppLockBridge {
    private const val TAG = "AppLockBridge"

    fun handle(context: Context, action: String, paramsJson: String): String {
        return try {
            val params = JSONObject(paramsJson.ifBlank { "{}" })
            when (action) {
                "lock" -> lock(context, params)
                "unlock" -> unlock(context, params)
                "list" -> list(context)
                "isLocked" -> isLocked(context, params)
                "consumeEvents" -> consumeEvents(context)
                else -> error("unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "appLock bridge error, action=$action", e)
            error(e.message ?: "Unknown error")
        }
    }

    private fun lock(context: Context, params: JSONObject): String {
        val pkg = params.optString("package", "").trim()
        if (pkg.isBlank()) return error("package is required")

        // 安全护栏：本应用与系统应用永远不可被插件锁定
        if (pkg == context.packageName) return error("refused: cannot lock the host app")
        val info = try {
            context.packageManager.getApplicationInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return error("package not installed: $pkg")
        }
        if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
            return error("refused: cannot lock system app")
        }

        val message = params.optString("message", "").trim()
        val requirePin = if (params.has("requirePin")) params.optBoolean("requirePin") else false

        AppLockStore.lockApp(context, pkg)
        AppLockStore.setRequirePin(context, pkg, requirePin)
        if (message.isNotBlank()) AppLockStore.setLockMessage(context, pkg, message)
        AppLockGuard.refresh()

        val label = appLabel(context, pkg)
        Log.i(TAG, "Plugin locked app: $label ($pkg), requirePin=$requirePin")
        return ok().put("package", pkg).put("app_name", label).toString()
    }

    private fun unlock(context: Context, params: JSONObject): String {
        val pkg = params.optString("package", "").trim()
        if (pkg.isBlank()) return error("package is required")
        AppLockStore.unlockApp(context, pkg)
        AppLockGuard.reArmLock(pkg)
        AppLockGuard.refresh()
        Log.i(TAG, "Plugin unlocked app: $pkg")
        return ok().put("package", pkg).toString()
    }

    private fun list(context: Context): String {
        val arr = JSONArray()
        AppLockStore.getLockedPackages(context).forEach { pkg ->
            arr.put(JSONObject().apply {
                put("package", pkg)
                put("app_name", appLabel(context, pkg))
                put("message", AppLockStore.getLockMessage(context, pkg) ?: "")
                put("require_pin", AppLockStore.getRequirePin(context, pkg))
            })
        }
        return ok().put("locked", arr).toString()
    }

    private fun isLocked(context: Context, params: JSONObject): String {
        val pkg = params.optString("package", "").trim()
        return ok().put("locked", AppLockStore.isLocked(context, pkg)).toString()
    }

    private fun consumeEvents(context: Context): String {
        val events = AppLockEventLog.consume(context)
        return ok().put("events", JSONArray(events)).toString()
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
