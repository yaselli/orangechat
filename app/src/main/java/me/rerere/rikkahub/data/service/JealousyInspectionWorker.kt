/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import java.util.concurrent.TimeUnit
import kotlin.math.max
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID

class JealousyInspectionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val state = JealousyInspectionStore.read(applicationContext)
        if (!state.enabled || state.reconciling || state.forcedOpen) return Result.success()
        if (state.managedPackages.isEmpty()) return Result.success()

        val now = System.currentTimeMillis()
        val silenceStart = state.silenceStartedAt.takeIf { it > 0L } ?: now.also {
            JealousyInspectionStore.startSilenceCycle(applicationContext, it)
        }
        val scoringStart = silenceStart + TimeUnit.MINUTES.toMillis(
            JealousyInspectionStore.START_DELAY_MINUTES.toLong(),
        )
        if (now < scoringStart) return Result.success()

        return runCatching {
            val managed = state.managedPackages - JealousyInspectionStore.effectiveWhitelist(applicationContext)
            val usage = calculateUsage(applicationContext, scoringStart, now, managed)
            val score = usage.score.coerceIn(state.score, JealousyInspectionState.MAX_SCORE)
            JealousyInspectionStore.recordInspection(applicationContext, score, usage.minutesByPackage)
            val stage = JealousyInspectionStore.stageForScore(score)
            if (stage > state.lastTriggeredStage) {
                setForeground(createForegroundInfo())
                triggerAiDecision(score, usage.minutesByPackage)
            }
            Result.success()
        }.getOrElse { error ->
            Log.e(TAG, "Jealousy inspection failed", error)
            Result.retry()
        }
    }

    private fun triggerAiDecision(score: Int, usage: Map<String, Int>) {
        val packageManager = applicationContext.packageManager
        val usageLines = usage.entries
            .sortedByDescending { it.value }
            .joinToString("\n") { (packageName, minutes) ->
                val label = runCatching {
                    val info = packageManager.getApplicationInfo(packageName, 0)
                    packageManager.getApplicationLabel(info).toString()
                }.getOrDefault(packageName)
                "- $label ($packageName): $minutes 分钟"
            }
        val contextText = buildString {
            appendLine("吃醋值：$score/100")
            appendLine("固定锁定阈值：70")
            appendLine("本轮可管理应用使用情况：")
            appendLine(usageLines.ifBlank { "- 没有可用记录" })
        }
        val intent = Intent(applicationContext, ProactiveMessageTriggerService::class.java)
            .putExtra(ProactiveMessageTriggerService.EXTRA_FORCE_TRIGGER, true)
            .putExtra(ProactiveMessageService.EXTRA_TRIGGER_SOURCE, "jealousy_inspection")
            .putExtra(ProactiveMessageTriggerService.EXTRA_JEALOUSY_CONTEXT, contextText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(
            applicationContext,
            CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
        )
            .setContentTitle("吃醋巡检中")
            .setSmallIcon(me.rerere.rikkahub.R.drawable.small_icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            20004,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        private const val TAG = "JealousyWorker"
        private const val UNIQUE_WORK = "jealousy_inspection_work"

        fun schedule(context: Context, delayMinutes: Int) {
            val request = PeriodicWorkRequestBuilder<JealousyInspectionWorker>(
                JealousyInspectionStore.INSPECTION_INTERVAL_MINUTES.toLong(),
                TimeUnit.MINUTES,
            )
                .setInitialDelay(delayMinutes.coerceAtLeast(1).toLong(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }
    }
}

private data class UsageCalculation(
    val minutesByPackage: Map<String, Int>,
    val score: Int,
)

private fun calculateUsage(
    context: Context,
    start: Long,
    end: Long,
    managed: Set<String>,
): UsageCalculation {
    if (managed.isEmpty() || end <= start) return UsageCalculation(emptyMap(), 0)
    val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    // 向前查看一天以确定“巡检开始时已经在前台”的应用，
    // 避免长时间停留在同一应用时漏算。
    val lookbackStart = (start - TimeUnit.DAYS.toMillis(1)).coerceAtLeast(0L)
    val events = manager.queryEvents(lookbackStart, end)
    val event = UsageEvents.Event()
    val totals = mutableMapOf<String, Long>()
    val longest = mutableMapOf<String, Long>()
    var activePackage: String? = null
    var activeSince = start

    fun closeActive(at: Long) {
        val packageName = activePackage ?: return
        val duration = (at - activeSince).coerceAtLeast(0L)
        if (packageName in managed) {
            totals[packageName] = totals.getOrDefault(packageName, 0L) + duration
            longest[packageName] = max(longest.getOrDefault(packageName, 0L), duration)
        }
    }

    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        when (event.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                closeActive(event.timeStamp)
                activePackage = event.packageName
                activeSince = max(event.timeStamp, start)
            }
            UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                if (event.packageName == activePackage) {
                    closeActive(event.timeStamp)
                    activePackage = null
                }
            }
        }
    }
    closeActive(end)

    val minutes = totals.mapValues { (_, duration) -> (duration / 60_000L).toInt() }
        .filterValues { it > 0 }
    val continuousBonuses = longest.values.count {
        it >= TimeUnit.MINUTES.toMillis(JealousyInspectionStore.CONTINUOUS_BONUS_MINUTES.toLong())
    }
    val score = JealousyScoreCalculator.calculate(
        totalUsageMillis = totals.values.sum(),
        continuousBonusCount = continuousBonuses,
    )
    return UsageCalculation(minutes, score)
}

internal object JealousyScoreCalculator {
    fun calculate(totalUsageMillis: Long, continuousBonusCount: Int): Int {
        val usedMinutes = (totalUsageMillis.coerceAtLeast(0L) / 60_000L).toInt()
        return usedMinutes +
            continuousBonusCount.coerceAtLeast(0) * JealousyInspectionStore.CONTINUOUS_BONUS_SCORE
    }
}
