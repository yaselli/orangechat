package me.rerere.rikkahub.data.ai

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    )

    private val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val hashBuffer = ByteArray(4096)
    private val previous = linkedMapOf<String, Snapshot>()
    private var sequence = 0L
    private var remainingCharacters = 0
    private class DiagnosticLimit : RuntimeException()
    private val messageFields = listOf("role", "content", "reasoning_content", "tool_calls", "tool_call_id", "name")
    private val roles = setOf("system", "developer", "user", "assistant", "tool")

    @Synchronized
    fun compare(scope: String, payload: JsonObject): CacheRequestComparison {
        val number = ++sequence
        remainingCharacters = maxCharacters
        return try {
            compareWithinBudget(number, scope, payload)
        } catch (_: DiagnosticLimit) {
            CacheRequestComparison(number, skipped = true)
        }
    }

    private fun compareWithinBudget(number: Long, scope: String, payload: JsonObject): CacheRequestComparison {
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
