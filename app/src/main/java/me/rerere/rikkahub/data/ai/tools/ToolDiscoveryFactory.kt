package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant

internal fun createToolDiscoverySession(
    assistant: Assistant,
    settings: Settings,
    model: Model,
    registered: List<Tool>,
): ToolDiscoverySession? =
    if (
        BuildConfig.DEBUG &&
            assistant.toolDiscoveryEnabled &&
            ModelAbility.TOOL in model.abilities &&
            (assistant.customBodies + model.customBodies).none {
                it.key in
                    setOf(
                        "tools",
                        "tool_choice",
                        "functions",
                        "function_call",
                        "messages",
                        "system",
                        "contents",
                        "input",
                    )
            }
    )
        ToolDiscoverySession(ToolDiscoveryPolicy.residentNames(assistant, settings, registered))
    else null

/** Proactive requests already have their own system prompt: decorate only the provider copy. */
internal fun List<UIMessage>.withToolDiscoveryDirectory(
    tools: List<Tool>,
    model: Model,
): List<UIMessage> {
    val text =
        tools
            .firstOrNull { it.name == ToolDiscoverySession.ENTRY }
            ?.systemPrompt
            ?.invoke(model, this)
            ?.takeIf { it.isNotBlank() } ?: return this
    val index = indexOfFirst { it.role == MessageRole.SYSTEM }
    if (index < 0) return listOf(UIMessage.system(text)) + this
    return mapIndexed { at, message ->
        if (at == index) message.copy(parts = message.parts + UIMessagePart.Text("\n$text"))
        else message
    }
}
