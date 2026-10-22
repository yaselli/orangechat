package me.rerere.rikkahub.data.interaction

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant

fun buildChatInteractionTools(
    assistant: Assistant,
    onAvatar: suspend () -> String,
    onReaction: suspend (String, String, Boolean, String) -> Unit,
): List<Tool> = buildList {
    if (assistant.enableAvatarInteractions) add(Tool(
        name = "avatar_interaction",
        description = "Gently interact with the user's avatar using their configured action and suffix. " +
            "The app displays a centered notice. Use naturally, do not repeatedly spam this tool.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }, required = emptyList()) },
        execute = {
            val text = onAvatar()
            listOf(UIMessagePart.Text(buildJsonObject {
                put("actor", "assistant"); put("target", "user"); put("text", text)
            }.toString()))
        },
    ))
    if (assistant.enableMessageReactions) add(Tool(
        name = "message_reaction",
        description = "Add or remove YOUR emoji reaction underneath one specific message. " +
            "This is independent of avatar interactions. You cannot remove the user's reactions. " +
            "Use last_user, last_assistant, by_quote (with a unique sentence), or an exact message ID.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("message_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact ID, last_user, last_assistant, or by_quote; default last_user")
                })
                put("quote", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional exact sentence to react to within a split message")
                })
                put("emoji", buildJsonObject {
                    put("type", "string"); put("enum", buildJsonArray { CHAT_REACTION_EMOJIS.forEach { add(it) } })
                })
                put("operation", buildJsonObject {
                    put("type", "string"); put("enum", buildJsonArray { add("add"); add("remove") })
                })
            }, required = listOf("emoji"))
        },
        execute = { input ->
            val values = input.jsonObject
            val target = values["message_id"]?.jsonPrimitive?.contentOrNull ?: "last_user"
            val emoji = values["emoji"]?.jsonPrimitive?.contentOrNull ?: error("emoji is required")
            val operation = values["operation"]?.jsonPrimitive?.contentOrNull ?: "add"
            require(operation == "add" || operation == "remove")
            onReaction(target, emoji, operation == "add", values["quote"]?.jsonPrimitive?.contentOrNull.orEmpty())
            listOf(UIMessagePart.Text(buildJsonObject { put("success", true) }.toString()))
        },
    ))
}
