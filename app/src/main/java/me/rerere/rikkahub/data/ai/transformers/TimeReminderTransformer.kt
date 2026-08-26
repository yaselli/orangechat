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

/** 当前时间与回复间隔是两个互不依赖的全局开关。 */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val setting = ctx.settings.systemToolsSetting
        return applyExtraTimeContext(
            messages = messages,
            currentTimeEnabled = setting.timeContextInjectionEnabled,
            replyIntervalEnabled = setting.replyIntervalReminderEnabled,
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

    val withReplyIntervals = if (replyIntervalEnabled) {
        insertReplyIntervalReminders(messages, thresholdSeconds)
    } else {
        messages
    }

    val latestUserIndex = withReplyIntervals.indexOfLast { it.role == MessageRole.USER }
    if (latestUserIndex == -1) return withReplyIntervals

    val timePolicy = if (currentTimeEnabled) {
        buildCurrentTimeContext(currentInstant)
    } else {
        UIMessage.user(
            "<time_policy>当前现实时间未被提供。不要根据历史中出现的时间推测现在几点，" +
                "也不要自行估算经过了多久。需要准确时间时调用已有的时间工具；" +
                "无法调用时坦诚不知道。</time_policy>",
        )
    }

    return buildList(withReplyIntervals.size + 1) {
        withReplyIntervals.forEachIndexed { index, message ->
            if (index == latestUserIndex) add(timePolicy)
            add(message)
        }
    }
}

private fun insertReplyIntervalReminders(
    messages: List<UIMessage>,
    thresholdSeconds: Long,
): List<UIMessage> = buildList {
    messages.forEachIndexed { index, message ->
        if (message.role == MessageRole.USER && index > 0) {
            val previousAssistant = messages.subList(0, index)
                .lastOrNull { it.role == MessageRole.ASSISTANT }
            if (previousAssistant != null) {
                val tz = TimeZone.currentSystemDefault()
                val userInstant = message.createdAt.toInstant(tz)
                val assistantEnd = (previousAssistant.finishedAt ?: previousAssistant.createdAt).toInstant(tz)
                val gapSeconds = (userInstant - assistantEnd).inWholeSeconds
                if (gapSeconds >= thresholdSeconds.coerceAtLeast(1L)) {
                    add(
                        UIMessage.user(
                            "<reply_interval>距离你上一条回复结束，聊天中的这个人过了" +
                                "${formatGap(gapSeconds)}才再次开口。自然理解这段间隔，" +
                                "不要机械复述时长，也不要提及这段提示。</reply_interval>",
                        ),
                    )
                }
            }
        }
        add(message)
    }
}

private fun buildCurrentTimeContext(instant: Instant): UIMessage {
    val javaInstant = instant.toJavaInstant()
    val dayOfWeek = javaInstant.atZone(ZoneId.systemDefault()).dayOfWeek
        .getDisplayName(TextStyle.FULL, Locale.getDefault())
    val timeStr = javaInstant.toLocalDateTime()
    return UIMessage.user(
        "<time_context>当前本地时间：$dayOfWeek，$timeStr。只把它当作必要时使用的背景信息，" +
            "除非对话确实涉及时间，否则不要机械提起当前几点，也不要提及这段提示。</time_context>",
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
