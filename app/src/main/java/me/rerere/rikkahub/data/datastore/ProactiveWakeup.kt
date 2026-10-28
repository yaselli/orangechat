package me.rerere.rikkahub.data.datastore

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.serialization.Serializable

@Serializable
enum class ProactiveWakeupStatus {
    Pending,
    Running,
    Sent,
    Failed,
    Cancelled,
}

@Serializable
data class ProactiveWakeup(
    val id: String,
    val assistantId: String,
    val conversationId: String,
    val atMillis: Long,
    val zoneId: String,
    val reason: String,
    val status: ProactiveWakeupStatus = ProactiveWakeupStatus.Pending,
    val notBeforeMillis: Long = atMillis,
    val outcome: String = "",
    val startedAtMillis: Long? = null,
)

internal val proactiveWakeupTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)

/** Reject impossible local times. During a repeated DST hour choose the first future occurrence. */
internal fun parseProactiveWakeupTime(text: String, zone: ZoneId, now: Long): Long {
    val local = LocalDateTime.parse(text, proactiveWakeupTimeFormat)
    val candidates = zone.rules.getValidOffsets(local).map { local.toInstant(it).toEpochMilli() }
    return candidates.filter { it > now }.minOrNull()
        ?: throw IllegalArgumentException("请指定未来的有效时间，格式为 yyyy-MM-dd HH:mm，按手机本地时区计算")
}

internal fun List<ProactiveWakeup>.trimWakeupHistory(): List<ProactiveWakeup> =
    filter { it.status in setOf(ProactiveWakeupStatus.Pending, ProactiveWakeupStatus.Running) } +
        filter { it.status !in setOf(ProactiveWakeupStatus.Pending, ProactiveWakeupStatus.Running) }
            .takeLast(10)

/** A new agreement replaces only this conversation's pending agreement, never another role's. */
internal fun List<ProactiveWakeup>.scheduleWakeup(wakeup: ProactiveWakeup): List<ProactiveWakeup> =
    (map { old ->
            if (
                old.conversationId == wakeup.conversationId &&
                    old.assistantId == wakeup.assistantId &&
                    old.status == ProactiveWakeupStatus.Pending
            )
                old.copy(status = ProactiveWakeupStatus.Cancelled, outcome = "已由新约定替换")
            else old
        } + wakeup)
        .trimWakeupHistory()

internal fun ProactiveMessageSetting.finishWakeup(
    id: String,
    outcome: String,
    now: Long,
): ProactiveMessageSetting =
    copy(
        aiWakeups =
            aiWakeups
                .map { wakeup ->
                    if (wakeup.id != id || wakeup.status != ProactiveWakeupStatus.Running) wakeup
                    else
                        when (outcome) {
                            "sent" ->
                                wakeup.copy(status = ProactiveWakeupStatus.Sent, outcome = "已发送")
                            "quiet_hours",
                            "generation_busy",
                            "user_returned",
                            "cancelled" ->
                                wakeup.copy(
                                    status = ProactiveWakeupStatus.Pending,
                                    notBeforeMillis = now + 60_000,
                                    outcome = "稍后重试",
                                )
                            else ->
                                wakeup.copy(
                                    status = ProactiveWakeupStatus.Failed,
                                    outcome =
                                        when (outcome) {
                                            "disabled",
                                            "wakeup_cancelled" -> "已关闭或取消主动唤醒"
                                            "no_model" -> "未配置聊天模型"
                                            "no_user_message",
                                            "no_conversation" -> "原会话已不存在或没有用户消息"
                                            "blocked_chat" -> "原会话处于拉黑状态"
                                            "pass",
                                            "wait",
                                            "stop" -> "AI 本次未发送正文，可重新安排"
                                            else -> "唤醒未完成，可重新安排"
                                        },
                                )
                        }
                }
                .trimWakeupHistory()
    )

/** A previous process may have sent before it died; don't silently repeat that paid generation. */
internal fun ProactiveMessageSetting.recoverWakeupsBefore(cutoff: Long): ProactiveMessageSetting =
    copy(
        aiWakeups =
            aiWakeups.map { wakeup ->
                if (
                    wakeup.status == ProactiveWakeupStatus.Running &&
                        (wakeup.startedAtMillis ?: 0L) < cutoff
                )
                    wakeup.copy(status = ProactiveWakeupStatus.Failed, outcome = "应用中断，未自动重发，可重新安排")
                else wakeup
            }
    )
