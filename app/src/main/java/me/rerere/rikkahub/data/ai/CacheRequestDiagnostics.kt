package me.rerere.rikkahub.data.ai

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Checksums only; labels are restricted to application-owned source/stage names. */
internal data class CacheSystemCheckpoint(val label: String, val checksum: String?, val stage: Boolean)

/** Local comparisons of prepared requests, never an estimate of provider cache hits. */
internal data class CacheRequestComparison(
    val requestNumber: Long,
    val baselineNumber: Long? = null,
    val systemChanged: Boolean = false,
    val toolsChanged: Boolean = false,
    val firstChangedTool: Int? = null,
    val unchangedMessages: Int = 0,
    val previousMessageCount: Int = 0,
    val messageCount: Int = 0,
    val firstChangedRole: String? = null,
    val firstChangedField: String? = null,
    val optionsChanged: Boolean = false,
    val skipped: Boolean = false,
    val changedSystemSources: List<String> = emptyList(),
    val firstChangedSystemStage: String? = null,
    val systemCheckpointsIncomplete: Boolean = false,
) {
    fun summary(): String = buildString {
        appendLine("请求 #$requestNumber" + (baselineNumber?.let { "，对照 #$it" } ?: ""))
        if (skipped) {
            append("未比较：请求格式不适用或超过诊断上限。")
            return@buildString
        }
        if (baselineNumber == null) {
            append("已建立本会话基线，等待下一次请求。")
            return@buildString
        }
        appendLine("系统提示：${if (systemChanged) "变化" else "相同"}")
        if (systemChanged) {
            if (changedSystemSources.isNotEmpty()) {
                appendLine("变化的系统组成：${changedSystemSources.joinToString("、")}")
            }
            firstChangedSystemStage?.let { appendLine("最早出现系统差异的阶段：$it") }
            if (systemCheckpointsIncomplete) appendLine("分段诊断不完整（存在未采集或超限阶段）。")
        }
        appendLine("工具定义：${if (toolsChanged) "变化" else "相同"}" +
            (firstChangedTool?.let { "，首个差异在第 ${it + 1} 项（含增删和顺序）" } ?: ""))
        when {
            firstChangedRole != null -> appendLine(
                "消息：前 $unchangedMessages 条相同；第 ${unchangedMessages + 1} 条 $firstChangedRole 的 $firstChangedField 变化。",
            )
            messageCount < previousMessageCount -> appendLine(
                "消息：前 $unchangedMessages 条相同，末尾减少 ${previousMessageCount - messageCount} 条。",
            )
            else -> appendLine("历史消息未改写，末尾新增 ${messageCount - previousMessageCount} 条。")
        }
        appendLine("其他请求体参数：${if (optionsChanged) "变化" else "相同"}")
        append("比较最终请求体（含自定义覆盖）；位置不是 token 位置，结果不代表服务端命中率。")
    }
}

/**
 * Retain only process-salted checksums and allowlisted roles, bounded to four conversation scopes.
 * No bodies, schema text, arguments, URLs, credentials or user-provided names are retained/logged.
 */
internal class CacheRequestDiagnostics(
    private val maxScopes: Int = 4,
    private val maxMessages: Int = 2048,
    private val maxTools: Int = 512,
    private val maxCharacters: Int = 4_000_000,
) {
    init {
        require(maxScopes > 0 && maxMessages > 0 && maxTools > 0 && maxCharacters > 0)
    }

    private data class MessageFingerprint(val hash: String, val role: String, val fields: List<String>)
    private data class Snapshot(
        val number: Long,
        val system: List<String>,
        val tools: List<String>,
        val toolSurface: String,
        val messages: List<MessageFingerprint>,
        val options: String,
        val checkpoints: List<CacheSystemCheckpoint>,
        val checkpointsIncomplete: Boolean,
    )

    private val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val hashBuffer = ByteArray(4096)
    private val previous = linkedMapOf<String, Snapshot>()
    private var sequence = 0L
    private var remainingCharacters = 0
    private class DiagnosticLimit : RuntimeException()
    private val messageFields = listOf("role", "content", "reasoning_content", "tool_calls", "tool_call_id", "name")
    private val roles = setOf("system", "developer", "user", "assistant", "tool")
    private val sourceLabels = setOf(
        "角色/会话设定", "旧整库记忆", "近期聊天标题", "代码与打包说明", "工具附加说明", "插件/额外背景",
        "界面/跳转/其他说明",
    )
    private val stageLabels = setOf(
        "系统初始组装后", "时间提醒后", "世界书/模式注入后", "占位符替换后", "文档转文本后", "图片识别后",
        "语音处理后", "消息模板后", "工作区说明后", "互动/音乐背景后", "记忆召回后",
    )

    /** Fingerprint immediately at the source; callers do not need to retain raw checkpoint text. */
    @Synchronized
    fun checkpoint(label: String, value: JsonElement, stage: Boolean = false): CacheSystemCheckpoint {
        val allowed = if (stage) stageLabels else sourceLabels
        val safeLabel = label.takeIf { it in allowed } ?: if (stage) "其他转换后" else "其他来源"
        remainingCharacters = minOf(maxCharacters, 500_000)
        val checksum = runCatching { hash(value) }.getOrNull()
        return CacheSystemCheckpoint(safeLabel, checksum, stage)
    }

    @Synchronized
    fun compare(
        scope: String, payload: JsonObject, checkpoints: List<CacheSystemCheckpoint> = emptyList(),
    ): CacheRequestComparison {
        val number = ++sequence
        remainingCharacters = maxCharacters
        return try {
            compareWithinBudget(number, scope, payload, checkpoints)
        } catch (_: DiagnosticLimit) {
            CacheRequestComparison(number, skipped = true)
        }
    }

    private fun compareWithinBudget(
        number: Long, scope: String, payload: JsonObject, checkpoints: List<CacheSystemCheckpoint>,
    ): CacheRequestComparison {
        val messages = payload["messages"] as? JsonArray
        val rawTools = payload["tools"]
        if (messages == null || messages.size > maxMessages ||
            (rawTools != null && rawTools !is JsonArray) ||
            (rawTools is JsonArray && rawTools.size > maxTools)
        ) return CacheRequestComparison(number, skipped = true)

        val fingerprints = messages.map { element ->
            val message = element as? JsonObject
                ?: return CacheRequestComparison(number, skipped = true)
            val role = (message["role"] as? JsonPrimitive)?.content
                ?.takeIf { it in roles } ?: "other"
            MessageFingerprint(hash(message), role, messageFields.map { hash(message[it]) })
        }
        val tools = (rawTools as? JsonArray).orEmpty().map(::hash)
        val snapshot = Snapshot(
            number = number,
            system = fingerprints.filter { it.role in setOf("system", "developer") }.map { it.hash },
            tools = tools,
            toolSurface = hash(rawTools),
            messages = fingerprints,
            options = hash(JsonObject(payload.filterKeys { it != "messages" && it != "tools" })),
            checkpoints = checkpoints.take(128).toList(),
            checkpointsIncomplete = checkpoints.isEmpty() || checkpoints.size > 128 ||
                checkpoints.any { it.checksum == null },
        )
        val scopeHash = hash(JsonPrimitive(scope))
        // Access order, so idle conversations are the first to be evicted.
        val baseline = previous.remove(scopeHash)
        previous[scopeHash] = snapshot
        while (previous.size > maxScopes) previous.remove(previous.keys.first())
        if (baseline == null) return CacheRequestComparison(number)

        val common = baseline.messages.zip(fingerprints).takeWhile { (old, new) -> old.hash == new.hash }.size
        val changed = common < minOf(baseline.messages.size, fingerprints.size)
        val changedField = if (changed) messageFields.indices.firstOrNull {
            baseline.messages[common].fields[it] != fingerprints[common].fields[it]
        }?.let(messageFields::get) ?: "其他字段或字段顺序" else null
        val toolChanged = baseline.toolSurface != snapshot.toolSurface
        val firstTool = if (toolChanged) {
            baseline.tools.zip(tools).takeWhile { (old, new) -> old == new }.size
        } else null
        val beforeSources = baseline.checkpoints.filterNot { it.stage }.groupBy { it.label }
        val afterSources = snapshot.checkpoints.filterNot { it.stage }.groupBy { it.label }
        val changedSources = (beforeSources.keys + afterSources.keys).filter { label ->
            beforeSources[label].orEmpty().all { it.checksum != null } &&
                afterSources[label].orEmpty().all { it.checksum != null } &&
                beforeSources[label] != afterSources[label]
        }
        val beforeStages = baseline.checkpoints.filter { it.stage }.groupBy { it.label }
        val afterStages = snapshot.checkpoints.filter { it.stage }.groupBy { it.label }
        val firstStage = (beforeStages.keys + afterStages.keys).firstOrNull { label ->
            beforeStages[label].orEmpty().all { it.checksum != null } &&
                afterStages[label].orEmpty().all { it.checksum != null } &&
                beforeStages[label] != afterStages[label]
        }
        return CacheRequestComparison(
            requestNumber = number,
            baselineNumber = baseline.number,
            systemChanged = baseline.system != snapshot.system,
            toolsChanged = toolChanged,
            firstChangedTool = firstTool,
            unchangedMessages = common,
            previousMessageCount = baseline.messages.size,
            messageCount = fingerprints.size,
            firstChangedRole = if (changed) fingerprints[common].role.uppercase() else null,
            firstChangedField = changedField,
            optionsChanged = baseline.options != snapshot.options,
            changedSystemSources = changedSources,
            firstChangedSystemStage = firstStage,
            systemCheckpointsIncomplete = baseline.checkpointsIncomplete || snapshot.checkpointsIncomplete,
        )
    }

    /** Hash the JSON tree incrementally, preserving types and object/array order, including media. */
    private fun hash(element: JsonElement?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val buffer = hashBuffer
        fun text(value: String) {
            val length = value.length
            if (length > remainingCharacters) throw DiagnosticLimit()
            remainingCharacters -= length
            repeat(4) { digest.update((length ushr (it * 8)).toByte()) }
            var used = 0
            value.forEach { char ->
                buffer[used++] = (char.code ushr 8).toByte()
                buffer[used++] = char.code.toByte()
                if (used == buffer.size) {
                    digest.update(buffer)
                    used = 0
                }
            }
            if (used > 0) digest.update(buffer, 0, used)
        }
        fun visit(value: JsonElement?, depth: Int) {
            if (depth >= 128) throw DiagnosticLimit()
            when (value) {
                null -> text("absent")
                is JsonObject -> {
                    text("object:${value.size}")
                    value.forEach { (key, child) -> text(key); visit(child, depth + 1) }
                }
                is JsonArray -> {
                    text("array:${value.size}")
                    value.forEach { visit(it, depth + 1) }
                }
                is JsonPrimitive -> {
                    text(if (value.isString) "string" else "primitive")
                    text(value.content)
                }
            }
        }
        try {
            visit(element, 0)
            return Base64.getEncoder().encodeToString(digest.digest())
        } finally {
            // The reusable scratch buffer must not retain pieces of prompt/media text.
            buffer.fill(0)
        }
    }
}
