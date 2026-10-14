package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState

/** Executes a call already admitted by the caller's approval policy. */
internal suspend fun executeGenerationTool(
    call: UIMessagePart.Tool,
    definition: Tool?,
    json: Json,
): UIMessagePart.Tool {
    currentCoroutineContext().ensureActive()
    fun failure(message: String) = call.copy(
        output = listOf(UIMessagePart.Text(buildJsonObject { put("error", message) }.toString()))
    )
    if (definition == null) return failure("Tool ${call.toolName} not found")
    val arguments = try {
        json.parseToJsonElement(call.input.ifBlank { "{}" })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return failure("Invalid tool arguments JSON for ${call.toolName}")
    }
    return try {
        val output = definition.execute(arguments)
        currentCoroutineContext().ensureActive()
        // UIMessagePart.Tool uses nonempty output as its completion marker.
        // Empty successful results must still be sent back with the original call ID.
        call.copy(output = output.ifEmpty {
            listOf(UIMessagePart.Text("Tool execution completed without output."))
        })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        failure("Tool ${call.toolName} failed: ${e.message ?: e.javaClass.simpleName}")
    }
}

/** Ordinary chat may resume with approval, refusal or an answer supplied by the user. */
internal suspend fun executeChatGenerationTool(
    tool: UIMessagePart.Tool,
    definition: Tool?,
    json: Json,
): UIMessagePart.Tool? {
    currentCoroutineContext().ensureActive()
    return when (tool.approvalState) {
        is ToolApprovalState.Denied -> {
            // Tool was denied by user
            val reason = (tool.approvalState as ToolApprovalState.Denied).reason
            tool.copy(
                output = listOf(
                    UIMessagePart.Text(
                        json.encodeToString(
                            buildJsonObject {
                                put(
                                    "error",
                                    JsonPrimitive(
                                        "Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}"
                                    )
                                )
                            }
                        )
                    )
                )
            )
        }

        is ToolApprovalState.Answered -> {
            // Tool was answered by user (e.g., ask_user tool)
            val answer = (tool.approvalState as ToolApprovalState.Answered).answer
            tool.copy(
                output = listOf(
                    UIMessagePart.Text(answer)
                )
            )
        }

        is ToolApprovalState.Pending -> null

        else -> {
            // Auto or Approved - execute the tool
            executeGenerationTool(
                call = tool,
                definition = definition,
                json = json,
            )
        }
    }
}
