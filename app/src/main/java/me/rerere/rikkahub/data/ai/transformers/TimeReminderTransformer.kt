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

private const val DEFAULT_REPLY_GAP_THRESHOLD_SECONDS = 3600L

/** 当前时间是全局开关；回复间隔恢复为每个助手独立设置。 */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val setting = ctx.settings.systemToolsSetting
        return applyExtraTimeContext(
            messages = messages,
            currentTimeEnabled = setting.timeContextInjectionEnabled,
            replyIntervalEnabled = ctx.assistant.enableTimeReminder,
            thresholdSeconds = ctx.assistant.timeReminderIntervalMinutes.coerceAtLeast(1) * 60L,
            currentInstant = Clock.System.now(),
        )
    }
}

internal fun applyExtraTimeContext(
    messages: List<UIMessage>,
    currentTimeEnabled: Boolean,
    replyIntervalEnabled: Boolean,
    thresholdSeconds: Long = DEFAULT_REPLY_GAP_THRESHOLD_SECONDS,
    currentInstant: Instant = Clock.System.now(),
): List<UIMessage> {
    if (messages.isEmpty()) return messages
    if (!currentTimeEnabled && !replyIntervalEnabled) return messages

    val withReplyIntervals = if (replyIntervalEnabled) {
        insertReplyIntervalReminders(messages, thresholdSeconds)
    } else {
        messages
    }

    val latestUserIndex = withReplyIntervals.indexOfLast { it.role == MessageRole.USER }
    if (latestUserIndex == -1) return withReplyIntervals

    return buildList(withReplyIntervals.size + if (currentTimeEnabled) 1 else 0) {
        withReplyIntervals.forEachIndexed { index, message ->
            if (index == latestUserIndex && currentTimeEnabled) add(buildCurrentTimeContext(currentInstant))
            add(message)
        }
    }
}

private fun insertReplyIntervalReminders(
    messages: List<UIMessage>,
    thresholdSeconds: Long,
): List<UIMessage> {
    val latestUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
    if (latestUserIndex <= 0) return messages
    val previousAssistant = messages.subList(0, latestUserIndex)
        .lastOrNull { it.role == MessageRole.ASSISTANT } ?: return messages
    val latestUser = messages[latestUserIndex]
    val tz = TimeZone.currentSystemDefault()
    val userInstant = latestUser.createdAt.toInstant(tz)
    val assistantEnd = (previousAssistant.finishedAt ?: previousAssistant.createdAt).toInstant(tz)
    val gapSeconds = (userInstant - assistantEnd).inWholeSeconds
    if (gapSeconds < thresholdSeconds.coerceAtLeast(1L)) return messages

    return messages.toMutableList().apply {
        add(
            latestUserIndex,
            UIMessage.user(
                "<reply_interval>距离你上一条回复结束，聊天中的这个人过了" +
                    "${formatGap(gapSeconds)}才再次开口。自然理解这段间隔，" +
                    "不要机械复述时长，也不要提及这段提示。</reply_interval>",
            ),
        )
    }
}

private fun buildCurrentTimeContext(instant: Instant): UIMessage {
    val javaInstant = instant.toJavaInstant()
    val dayOfWeek = javaInstant.atZone(ZoneId.systemDefault()).dayOfWeek
        .getDisplayName(TextStyle.FULL, Locale.getDefault())
    val timeStr = javaInstant.toLocalDateTime()
    return UIMessage.user(
        "<time_context>当前本地时间：$dayOfWeek，$timeStr</time_context>",
    )
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
