/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.ui.activity.AppLockUnlockActivity
import me.rerere.rikkahub.workflow.trigger.AppForegroundDispatcher

object AppLockGuard {
    private const val TAG = "AppLockGuard"
    private const val REENTRY_GUARD_MS = 1_000L

    private lateinit var appContext: Context
    private val unlockedPackages = java.util.concurrent.CopyOnWriteArraySet<String>()

    @Volatile private var listening = false
    @Volatile private var lastInterceptAt: Long = 0L

    /**
     * 锁定列表的内存缓存。
     * 前台切换是热路径，不能每次都读 SharedPreferences + 解析；
     * 由 [refresh] 在所有锁定状态变更后重建。
     */
    @Volatile private var lockedCache: Set<String> = emptySet()

    private val listener: (String?) -> Unit = { pkg -> onForegroundChange(pkg) }

    fun init(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    fun refresh() {
        if (!::appContext.isInitialized) return
        lockedCache = AppLockStore.getLockedPackages(appContext)
        val shouldListen = lockedCache.isNotEmpty()
        if (shouldListen && !listening) {
            AppForegroundDispatcher.addListener(listener)
            listening = true
        } else if (!shouldListen && listening) {
            AppForegroundDispatcher.removeListener(listener)
            listening = false
        }
    }

    fun grantGraceUnlock(packageName: String) {
        unlockedPackages.add(packageName)
    }

    fun reArmLock(packageName: String) {
        unlockedPackages.remove(packageName)
    }

    fun goHome() {
        if (!::appContext.isInitialized) return
        runCatching {
            RikkaAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }.onFailure { Log.w(TAG, "GLOBAL_ACTION_HOME failed", it) }
    }

    private fun onForegroundChange(pkg: String?) {
        if (pkg.isNullOrBlank()) return
        if (!::appContext.isInitialized) return
        // 本应用事件（包括锁屏悬浮窗、解锁页自身产生的窗口事件）一律忽略。
        // 关键：绝不在此隐藏悬浮窗 —— 悬浮窗的消失只允许由解锁页生命周期驱动，
        // 否则"悬浮窗出现 -> 本应用事件 -> 隐藏悬浮窗 -> 被锁应用又回前台 -> 再盖"
        // 会形成无限闪烁循环（历史 bug）。
        if (pkg == appContext.packageName) return

        if (pkg !in lockedCache) {
            // 用户已经离开被锁应用（回到桌面/去了别的正常应用），收掉止血悬浮窗。
            // 这是唯一的事件驱动隐藏路径，且只发生在非本应用、非锁定应用的前台事件上，
            // 不会与拦截动作形成回路。
            RikkaAccessibilityService.instance?.hideLockOverlay()
            return
        }
        if (pkg in unlockedPackages) return

        val now = System.currentTimeMillis()
        if (now - lastInterceptAt < REENTRY_GUARD_MS) return
        lastInterceptAt = now
        Log.i(TAG, "Intercepting locked app: $pkg")

        // 三段式拦截：
        // 1) 止血 —— 无障碍服务内同步盖全屏悬浮窗（<50ms，先盖住再说）
        runCatching { RikkaAccessibilityService.instance?.showLockOverlay(pkg) }
            .onFailure { Log.w(TAG, "showLockOverlay failed", it) }
        // 2) 踢出 —— 把被锁应用回桌面，让底下的应用真正停下来
        goHome()
        // 3) 落锁 —— 启动完整锁屏 Activity 展示留言（慢也无妨，屏幕已被盖住）
        runCatching {
            val intent = Intent(appContext, AppLockUnlockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(AppLockUnlockActivity.EXTRA_TARGET_PACKAGE, pkg)
            }
            appContext.startActivity(intent)
        }.onFailure { Log.e(TAG, "Failed to start unlock activity", it) }
    }
}
