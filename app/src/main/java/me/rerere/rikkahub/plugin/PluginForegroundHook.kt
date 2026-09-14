/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.workflow.trigger.AppForegroundDispatcher

/**
 * 插件事件钩子 - app_foreground
 *
 * 把无障碍服务的前台应用切换事件桥接给订阅了 "app_foreground" 的插件。
 * 通用能力，不含任何具体业务逻辑。
 *
 * 节流策略：同一时间只允许一次派发在途；派发期间到达的新事件只保留最新包名，
 * 派发结束后如有滞留则补发一次。插件钩子本身还有 16.5s 超时兜底，
 * 双重保护避免无障碍事件风暴把插件单线程打满。
 */
object PluginForegroundHook {
    private const val TAG = "PluginForegroundHook"
    private const val EVENT = "app_foreground"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dispatchMutex = Mutex()

    @Volatile private var loaderProvider: (() -> PluginLoader)? = null
    @Volatile private var pendingPackage: String? = null
    @Volatile private var started = false

    private val listener: (String?) -> Unit = { pkg ->
        if (!pkg.isNullOrBlank()) dispatch(pkg)
    }

    /**
     * 在 Application.onCreate 中调用。
     * [provider] 惰性提供 PluginLoader，避免启动期过早触发 Koin 构建。
     */
    fun init(provider: () -> PluginLoader) {
        if (started) return
        started = true
        loaderProvider = provider
        AppForegroundDispatcher.addListener(listener)
        Log.i(TAG, "PluginForegroundHook registered")
    }

    private fun dispatch(packageName: String) {
        scope.launch {
            // 派发中则暂存最新包名，结束后补发
            if (!dispatchMutex.tryLock()) {
                pendingPackage = packageName
                return@launch
            }
            try {
                var current: String? = packageName
                while (current != null) {
                    fireToPlugins(current)
                    current = pendingPackage
                    pendingPackage = null
                }
            } finally {
                dispatchMutex.unlock()
            }
        }
    }

    private suspend fun fireToPlugins(packageName: String) {
        val loader = try {
            loaderProvider?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "PluginLoader not ready", e)
            null
        } ?: return
        if (!loader.hasHookSubscribers(EVENT)) return
        runCatching {
            loader.callEvent(
                EVENT,
                buildJsonObject {
                    put("package", packageName)
                    put("timestamp", System.currentTimeMillis())
                },
            )
        }.onFailure { Log.w(TAG, "app_foreground dispatch failed", it) }
    }
}
