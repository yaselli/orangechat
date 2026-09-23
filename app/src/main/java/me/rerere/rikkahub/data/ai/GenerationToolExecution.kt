package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

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
