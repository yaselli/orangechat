/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String) -> AssistantMemory,
    onUpdate: suspend (Int, String) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit,
    onRead: (suspend (Int) -> AssistantMemory)? = null,
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores long-term information across conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove), `read` (fetch the complete record before editing a recalled excerpt).
            - No relevant record: `create` + `content`
            - Existing relevant record: `edit` + `id` + `content`
            - Outdated/irrelevant record: `delete` + `id`
            For create/edit, write memories from your own perspective as the assistant, not as an outside narrator.
            In Chinese, refer to yourself as “我” and the user as “她”.
            Do not use your character name or “用户” as the narrator's subject.
            “我” in a saved memory means the assistant; do not mistake the user's quoted “我” for yourself.
            Preserve who did what and the names of other people. Store only supported facts;
            do not invent experiences or promises to fit this perspective.
            Memories may be retrieved as partial background excerpts in later conversations.
            A recalled excerpt is NOT the full record: read the complete record before editing it.
            Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political views, sex life, criminal records).
            You may store: preferred name, preferences, plans, work-related notes, chat style preferences, first chat time, etc.
            Do not show memory content directly in the conversation unless the user explicitly asks.
            Today is ${LocalDate.now().toLocalString(true)}.
            Similar memories should be merged; prefer updating existing records.

            Examples:
            {"action":"create","content":"她喜欢我简短地回复，周末更常来找我聊天。"}
            {"action":"edit","id":12,"content":"她希望我称呼她为阿星，并用中文回复她。"}
            {"action":"delete","id":7}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                                if (onRead != null) add("read")
                            }
                        )
                        put("description", "Operation to perform: create, edit, delete, or read")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete/read)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Memory content from the assistant's perspective: 我 = assistant, 她 = user (required for create/edit)"
                        )
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload = when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content))
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onUpdate(id, content))
                }

                "read" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val read = onRead ?: error("Reading memory is not available")
                    json.encodeToJsonElement(AssistantMemory.serializer(), read(id))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete, read]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
