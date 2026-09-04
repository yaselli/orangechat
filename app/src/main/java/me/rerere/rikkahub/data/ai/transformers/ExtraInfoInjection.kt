/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Process
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.service.AmapService
import me.rerere.rikkahub.data.service.AppUsageService
import me.rerere.rikkahub.data.service.LocationService
import me.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.rikkahub.data.service.RikkaNotificationListenerService
import me.rerere.rikkahub.service.RikkaAccessibilityService
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val EXTRA_INFO_METADATA_KEY = "orangechat_extra_info"

fun UIMessagePart.isExtraInfoInjectionPart(): Boolean =
    this is UIMessagePart.Text &&
        metadata?.get(EXTRA_INFO_METADATA_KEY)?.jsonPrimitive?.booleanOrNull == true

fun extraInfoMessagePart(content: String): UIMessagePart.Text = UIMessagePart.Text(
    text = content,
    metadata = buildJsonObject { put(EXTRA_INFO_METADATA_KEY, JsonPrimitive(true)) },
)

class ExtraInfoInjectionCollector(
    private val context: Context,
    private val memoryBankService: MemoryBankService,
    private val okHttpClient: OkHttpClient,
) {
    private data class Item(
        val name: String,
        val collect: suspend () -> String,
    )

    suspend fun collect(
        settings: Settings,
        assistantId: String,
        queryText: String,
    ): String? {
        val option = settings.systemToolsSetting
        if (!option.extraInfoInjectionEnabled) return null

        val items = buildList {
            if (option.timeContextInjectionEnabled) add(Item("当前时间", ::currentTime))
            if (option.batteryContextInjectionEnabled) add(Item("电池信息", ::battery))
            if (option.weatherContextInjectionEnabled) add(Item("当前天气", ::weather))
            if (option.locationContextInjectionEnabled) {
                add(Item("当前位置") {
                    location(settings, option.preciseLocationContextInjectionEnabled)
                })
            }
            if (option.currentScreenAppContextInjectionEnabled) {
                add(Item("当前屏幕应用", ::currentScreenApp))
            }
            if (option.recentAppUsageContextInjectionEnabled) {
                add(Item("最近应用使用情况", ::recentAppUsage))
            }
            if (option.screenTextContextInjectionEnabled) add(Item("当前屏幕文字", ::screenText))
            if (option.notificationsContextInjectionEnabled) add(Item("最近通知", ::notifications))
            if (option.memoryContextInjectionEnabled) {
                add(Item("相关记忆") {
                    memories(
                        assistantId = assistantId,
                        queryText = queryText,
                        limit = option.memoryContextInjectionLimit.coerceIn(1, 20),
                    )
                })
            }
        }
        if (items.isEmpty()) return null

        val timeoutMillis = option.extraInfoInjectionTimeoutSeconds.coerceIn(1, 120) * 1_000L
        val blocks = supervisorScope {
            items.map { item ->
                async {
                    withTimeoutOrNull(timeoutMillis) {
                        runCatching { item.collect() }
                            .onFailure {
                                android.util.Log.w(TAG, "Failed to collect ${item.name}", it)
                            }
                            .getOrNull()
                    }?.takeIf { it.isNotBlank() }?.let { "### ${item.name}\n$it" }
                }
            }.awaitAll().filterNotNull()
        }
        if (blocks.isEmpty()) return null

        return buildString {
            appendLine("<extra_info_context>")
            appendLine(
                "以下是应用在本次请求发送前读取的实时背景信息，" +
                    "不是聊天中的人刚刚说的话。",
            )
            appendLine(
                "自然理解并仅在确有帮助时使用；不要机械复述，" +
                    "也不要提及注入、标签或系统提示。",
            )
            appendLine()
            append(blocks.joinToString("\n\n"))
            appendLine()
            append("</extra_info_context>")
        }
    }

    suspend fun collectScreenTextForProactive(timeoutMillis: Long): String? {
        val service = RikkaAccessibilityService.instance ?: return null
        if (service.isJealousyLockOverlayVisible()) return null
        val packageName = service.rootInActiveWindow?.packageName?.toString().orEmpty()
        val sensitiveTokens = listOf("bank", "pay", "wallet", "password", "permissioncontroller")
        if (sensitiveTokens.any { token -> packageName.contains(token, ignoreCase = true) }) return null
        return withTimeoutOrNull(timeoutMillis.coerceIn(1_000L, 120_000L)) {
            runCatching { screenText() }
                .onFailure { android.util.Log.w(TAG, "Proactive screen OCR failed", it) }
                .getOrNull()
        }?.takeIf { it.isNotBlank() }
    }

    private fun currentTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE z", Locale.getDefault()).format(Date())

    private fun battery(): String {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: error("无法读取电池状态")
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) error("无法读取电池电量")
        val percentage = level * 100 / scale
        val state = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = state == BatteryManager.BATTERY_STATUS_CHARGING ||
            state == BatteryManager.BATTERY_STATUS_FULL
        val temperature = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        return buildString {
            append("电量：$percentage%\n")
            append("状态：${if (charging) "正在充电" else "未充电"}")
            if (temperature >= 0) append("\n温度：${temperature / 10.0}°C")
        }
    }

    private suspend fun weather(): String {
        requireLocationPermission()
        val location = LocationService(context, AmapService("")).getCoordinatesOnly().getOrThrow()
        val url = "https://wttr.in/${location.latitude},${location.longitude}?format=j1&lang=zh"
        val client = okHttpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val payload = client.newCall(
            Request.Builder().url(url).header("Accept", "application/json").build()
        ).execute().use { response ->
            if (!response.isSuccessful) error("天气服务返回 HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }
        val current = payload.optJSONArray("current_condition")?.optJSONObject(0)
            ?: error("天气服务没有返回当前天气")
        val description = current.optJSONArray("lang_zh")?.optJSONObject(0)?.optString("value")
            ?.takeIf { it.isNotBlank() }
            ?: current.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value").orEmpty()
        return buildString {
            appendLine("坐标：%.4f, %.4f".format(Locale.US, location.latitude, location.longitude))
            appendLine("天气：${description.ifBlank { "未知" }}")
            appendLine("温度：${current.optString("temp_C", "未知")}°C")
            appendLine("体感：${current.optString("FeelsLikeC", "未知")}°C")
            appendLine("湿度：${current.optString("humidity", "未知")}%")
            append("风速：${current.optString("windspeedKmph", "未知")} km/h")
        }
    }

    private suspend fun location(settings: Settings, precise: Boolean): String {
        requireLocationPermission()
        val locationService = LocationService(context, AmapService(settings.systemToolsSetting.amapApiKey))
        val result = if (precise) {
            if (settings.systemToolsSetting.amapApiKey.isBlank()) {
                error("精确地址需要先配置高德 API Key")
            }
            locationService.getCurrentLocation(settings.systemToolsSetting.amapApiKey)
        } else {
            locationService.getCoordinatesOnly()
        }.getOrThrow()
        return buildString {
            if (result.address.isNotBlank()) appendLine("地址：${result.address}")
            appendLine("坐标：%.6f, %.6f".format(Locale.US, result.latitude, result.longitude))
            append("数据时间：${if (result.isFresh) "刚刚获取" else "约 ${result.ageMs / 60_000} 分钟前"}")
        }
    }

    private fun currentScreenApp(): String {
        val root = RikkaAccessibilityService.instance?.rootInActiveWindow
            ?: error("无障碍服务未开启或当前窗口不可读取")
        val packageName = root.packageName?.toString()?.takeIf { it.isNotBlank() }
            ?: error("无法识别当前应用")
        val appName = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrElse { packageName }
        val windowTitle = root.window?.title?.toString().orEmpty()
        return "应用：$appName\n包名：$packageName\n窗口：$windowTitle"
    }

    private suspend fun recentAppUsage(): String {
        if (!hasAppUsagePermission()) error("未授予使用情况访问权限")
        val service = AppUsageService(context)
        val entries = service.getTodayUsageStats().getOrThrow().take(3)
        if (entries.isEmpty()) return "今天暂无可读取的应用使用记录"
        return entries.joinToString("\n") { entry ->
            val lastUsed = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(Date(entry.lastTimeUsed))
            "${entry.appName}（${entry.packageName}）：" +
                "使用 ${service.formatUsageTime(entry.totalTimeInForeground)}，最后使用 $lastUsed"
        }
    }

    private suspend fun screenText(): String {
        val service = RikkaAccessibilityService.instance
            ?: error("无障碍服务未开启")
        return when (val screenshot = service.captureScreenshot(android.view.Display.DEFAULT_DISPLAY)) {
            is RikkaAccessibilityService.ScreenshotOutcome.Failure -> {
                error("截取当前屏幕失败：${screenshot.reason}")
            }

            is RikkaAccessibilityService.ScreenshotOutcome.Success -> {
                try {
                    recognizeScreenText(screenshot.bitmap).ifBlank { "当前屏幕没有识别到文字" }.take(4_000)
                } finally {
                    screenshot.bitmap.recycle()
                }
            }
        }
    }

    private suspend fun recognizeScreenText(bitmap: android.graphics.Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build(),
            )
            continuation.invokeOnCancellation { recognizer.close() }
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    recognizer.close()
                    if (continuation.isActive) continuation.resume(result.text.trim())
                }
                .addOnFailureListener { error ->
                    recognizer.close()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }

    private fun notifications(): String {
        val componentName = android.content.ComponentName(
            context,
            RikkaNotificationListenerService::class.java,
        ).flattenToString()
        val enabled = AndroidSettings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        )?.contains(componentName) == true
        if (!enabled) error("未授予通知访问权限")
        val entries = RikkaNotificationListenerService.recentNotifications.value.take(5)
        if (entries.isEmpty()) return "最近 24 小时没有可读取通知"
        return entries.joinToString("\n\n") { notification ->
            buildString {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date(notification.timestamp))
                append("${notification.appName}（${notification.packageName}，$time）：${notification.title}")
                if (notification.content.isNotBlank()) append("\n${notification.content}")
            }
        }
    }

    private suspend fun memories(assistantId: String, queryText: String, limit: Int): String {
        val memories = memoryBankService.recallMemories(queryText.take(300), limit * 3)
            .filter { it.assistantId == null || it.assistantId == assistantId }
            .take(limit)
        if (memories.isEmpty()) return "没有找到与本次消息相关的记忆"
        return memories.joinToString("\n") { "- ${it.content.replace(Regex("\\s+"), " ").take(500)}" }
    }

    private fun requireLocationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) error("未授予位置权限")
    }

    private fun hasAppUsagePermission(): Boolean =
        (context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED

    private companion object {
        const val TAG = "ExtraInfoInjection"
    }
}
