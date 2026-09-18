package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.rikkahub.data.model.Assistant

/** Shared by chat and proactive generation; provider-specific serialization stays in the provider. */
internal fun buildGenerationRequestParameters(
    assistant: Assistant,
    model: Model,
    tools: List<Tool>,
): TextGenerationParams = TextGenerationParams(
    model = model,
    temperature = assistant.temperature,
    topP = assistant.topP,
    maxTokens = assistant.maxTokens,
    tools = tools,
    reasoningLevel = assistant.reasoningLevel,
    // Preserve duplicates and ordering: model overrides are appended after assistant overrides.
    customHeaders = assistant.customHeaders + model.customHeaders,
    customBody = assistant.customBodies + model.customBodies,
)
