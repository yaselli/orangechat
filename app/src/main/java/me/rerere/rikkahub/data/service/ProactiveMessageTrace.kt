/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.model.Conversation
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

/**
 * Privacy-safe trace for one proactive generation.
 *
 * Message/worldbook contents, tool arguments/results, request headers and credentials are never
 * recorded. Android logcat receives checkpoints immediately; the in-app Logs page receives one
 * compact summary when the run finishes.
 */
internal class ProactiveMessageTrace private constructor(
    private val runId: String,
    source: String,
) {
    private val startedAt = System.currentTimeMillis()
    private val lines = mutableListOf<String>()
    private val finished = AtomicBoolean(false)

    init {
        event("start", "source=$source")
    }

    fun event(name: String, details: String = "") {
        val elapsed = System.currentTimeMillis() - startedAt
        val line = buildString {
            append('+').append(elapsed).append("ms ").append(name)
            if (details.isNotBlank()) append(' ').append(details)
        }
        synchronized(lines) { lines += line }
        Log.i(TAG, "[$runId] $line")
    }

    fun conversation(label: String, conversation: Conversation?) {
        if (conversation == null) {
            event(label, "conversation=null")
            return
        }
        val current = conversation.currentMessages
        event(
            label,
            "conversation=${safeId(conversation.id)} nodes=${conversation.messageNodes.size} " +
                messageSummary(current),
        )
    }

    fun messages(label: String, messages: List<UIMessage>) {
        event(label, messageSummary(messages))
    }

    fun finish(outcome: String, error: Throwable? = null) {
        if (!finished.compareAndSet(false, true)) return
        error?.let {
            event(
                "error",
                "type=${it::class.simpleName.orEmpty()} message=${sanitizeError(it.message)}",
            )
        }
        event("finish", "outcome=$outcome")
        val summary = synchronized(lines) { lines.toList() }.joinToString("\n")
        Logging.log(TAG, "[$runId]\n$summary")
    }

    companion object {
        const val TAG = "ProactiveMessageTrace"

        fun start(source: String): ProactiveMessageTrace {
            val suffix = Uuid.random().toString().replace("-", "").takeLast(4).uppercase(Locale.ROOT)
            return ProactiveMessageTrace("PM-$suffix", source)
        }

        fun safeId(value: Any?): String = value?.toString()?.hashCode()
            ?.toUInt()?.toString(16)?.padStart(8, '0')?.takeLast(8) ?: "null"

        fun messageShape(message: UIMessage): String {
            val partCounts = message.parts.groupingBy { partName(it) }.eachCount()
                .entries.joinToString("+") { (name, count) -> if (count == 1) name else "$name$count" }
                .ifBlank { "Empty" }
            return "${message.role.name.first()}#${safeId(message.id)}[$partCounts]"
        }

        private fun messageSummary(messages: List<UIMessage>): String {
            val roles = messages.groupingBy { it.role.name }.eachCount()
                .entries.joinToString(",") { (role, count) -> "$role=$count" }
            val parts = messages.asSequence().flatMap { it.parts.asSequence() }
                .groupingBy(::partName).eachCount()
                .entries.joinToString(",") { (part, count) -> "$part=$count" }
            val tail = messages.takeLast(6).joinToString(",", transform = ::messageShape)
            return "messages=${messages.size} roles=[$roles] parts=[$parts] tail=[$tail]"
        }

        private fun partName(part: UIMessagePart): String = when (part) {
            is UIMessagePart.Text -> "Text"
            is UIMessagePart.Reasoning -> "Reasoning"
            is UIMessagePart.Tool -> "Tool"
            is UIMessagePart.Image -> "Image"
            is UIMessagePart.Document -> "Document"
            is UIMessagePart.Audio -> "Audio"
            is UIMessagePart.Video -> "Video"
            is UIMessagePart.VoiceMessage -> "Voice"
            else -> part::class.simpleName ?: "Part"
        }

        private fun sanitizeError(message: String?): String = message.orEmpty()
            .replace(Regex("(?i)bearer\\s+[^\\s,}]+"), "Bearer <redacted>")
            .replace(Regex("(?i)(api[_-]?key|authorization)[=:]\\s*[^\\s,}]+"), "\$1=<redacted>")
            .take(400)
    }
}
