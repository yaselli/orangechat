/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.toJavaInstant

private const val DEFAULT_TIME_GAP_THRESHOLD_SECONDS = 300L

/**
 * 时间提醒注入转换器
 *
 * 独立开关位于扩展管理。开启后，每次请求只注入一条精简的当前时间上下文；
 * 若用户与上一条 assistant 消息之间存在较长间隔，再附带真实回复间隔。
 * 不扫描并注入整段历史，避免 token 随聊天长度增长。
 */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val globalSetting = ctx.settings.systemToolsSetting
        if (!globalSetting.timeContextInjectionEnabled) return messages
        val thresholdSeconds = globalSetting.timeContextInjectionIntervalMinutes
            .coerceAtLeast(1)
            .toLong() * 60L
        return applyTimeReminder(messages, thresholdSeconds, Clock.System.now())
    }
}

internal fun applyTimeReminder(
    messages: List<UIMessage>,
    thresholdSeconds: Long = DEFAULT_TIME_GAP_THRESHOLD_SECONDS,
    currentInstant: Instant = Clock.System.now(),
): List<UIMessage> {
    val latestUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
    if (latestUserIndex == -1) return messages
    val tz = TimeZone.currentSystemDefault()

    val gapSeconds = if (latestUserIndex > 0) {
        val latestUser = messages[latestUserIndex]
        val previousAssistant = messages.subList(0, latestUserIndex)
            .lastOrNull { it.role == MessageRole.ASSISTANT }

        if (previousAssistant != null) {
            val latestUserInstant = latestUser.createdAt.toInstant(tz)
            val previousEndInstant = (previousAssistant.finishedAt ?: previousAssistant.createdAt).toInstant(tz)
            val measuredGap = (latestUserInstant - previousEndInstant).inWholeSeconds

            measuredGap.takeIf { it >= thresholdSeconds.coerceAtLeast(1L) }
        } else {
            null
        }
    } else {
        null
    }

    val reminder = buildTimeReminderMessage(gapSeconds, currentInstant)
    return buildList(messages.size + 1) {
        messages.forEachIndexed { index, message ->
            if (index == latestUserIndex) {
                add(reminder)
            }
            add(message)
        }
    }
}

private fun buildTimeReminderMessage(gapSeconds: Long?, instant: Instant): UIMessage {
    val javaInstant = instant.toJavaInstant()
    val dayOfWeek = javaInstant.atZone(ZoneId.systemDefault()).dayOfWeek
        .getDisplayName(TextStyle.FULL, Locale.getDefault())
    val timeStr = javaInstant.toLocalDateTime()
    val content = if (gapSeconds != null) {
        val gapText = formatGap(gapSeconds)
        "<time_context>Current time: $dayOfWeek, $timeStr. " +
            "The user replied $gapText after your previous message finished. " +
            "Understand the elapsed time naturally. Do not mechanically repeat the duration " +
            "and do not mention this context or any technical implementation.</time_context>"
    } else {
        "<time_context>Current time: $dayOfWeek, $timeStr. " +
            "Use it naturally when relevant and never mention this technical context.</time_context>"
    }
    return UIMessage.user(content)
}

private fun formatGap(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val days = safeSeconds / 86400
    val hours = (safeSeconds % 86400) / 3600
    val minutes = (safeSeconds % 3600) / 60
    val remainingSeconds = safeSeconds % 60

    return buildString {
        if (days > 0) append("${days}天")
        if (hours > 0 || days > 0) append("${hours}小时")
        if (minutes > 0 || hours > 0 || days > 0) append("${minutes}分")
        append("${remainingSeconds}秒")
    }
}
