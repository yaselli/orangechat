package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.rikkahub.data.ai.tools.ToolDiscoverySession
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal data class GenerationToolApproval(
    val calls: List<UIMessagePart.Tool>,
    val waitingForApproval: Boolean = false,
)

internal enum class GenerationToolUpdate { APPROVAL, RESULTS }

/**
 * Shared model -> tools -> model loop. Entry callbacks retain transforms, approval policy,
 * message ownership and persistence. Failures and cancellation propagate to entry cleanup.
 *
 * Ordinary chat counts all iterations (including approval resumption) towards maxSteps.
 * Proactive generation reserves one final model turn, which may reply but cannot execute tools.
 */
internal suspend fun runGenerationToolLoop(
    initialMessages: List<UIMessage>,
    maxSteps: Int,
    reserveFinalModelTurn: Boolean = false,
    resumeApprovedTools: Boolean = false,
    toolLimitError: String = "Tool limit reached before a final reply",
    toolsForStep: suspend (Int) -> List<Tool>,
    generateTurn: suspend (List<UIMessage>, List<Tool>, Int) -> List<UIMessage>,
    prepareTools: (List<UIMessagePart.Tool>, List<Tool>) -> GenerationToolApproval = { calls, _ ->
        GenerationToolApproval(calls)
    },
    executeTool: suspend (UIMessagePart.Tool, Tool?, Int) -> UIMessagePart.Tool?,
    onToolsUpdated: suspend (List<UIMessage>, GenerationToolUpdate) -> Unit,
    onStep: suspend (Int, Boolean) -> Unit = { _, _ -> },
    discovery: ToolDiscoverySession? = null,
): List<UIMessage> {
    var messages = initialMessages
    val steps = if (reserveFinalModelTurn) 0..maxSteps else 0 until maxSteps
    for (step in steps) {
        currentCoroutineContext().ensureActive()
        val finalTurn = reserveFinalModelTurn && step == maxSteps
        onStep(step, finalTurn)
        val available = toolsForStep(step)
        // Approval pauses the whole batch, including Auto calls. Resume every unfinished
        // call through the approval policy before requesting another model turn.
        val unfinished = if (resumeApprovedTools) {
            messages.lastOrNull()?.getTools()?.filter { !it.isExecuted }.orEmpty()
        } else {
            emptyList()
        }

        val definitions = discovery?.expose(available, unfinished.map { it.toolName }.toSet()) ?: available
        val generated = if (unfinished.isEmpty()) {
            messages = generateTurn(messages, definitions, step)
            messages.lastOrNull()?.getTools()?.filter { !it.isExecuted }.orEmpty()
        } else {
            unfinished
        }
        if (generated.isEmpty()) return messages
        if (finalTurn) error(toolLimitError)
        val approval = prepareTools(generated, definitions)
        if (approval.calls != generated) {
            messages = replaceGenerationTools(messages, approval.calls)
            onToolsUpdated(messages, GenerationToolUpdate.APPROVAL)
        }
        if (approval.waitingForApproval) return messages
        val calls = approval.calls

        val results = mutableListOf<UIMessagePart.Tool>()
        for (call in calls) {
            currentCoroutineContext().ensureActive()
            executeTool(call, definitions.find { it.name == call.toolName }, step)?.let(results::add)
        }
        if (results.isEmpty()) return messages
        messages = replaceGenerationTools(messages, results)
        onToolsUpdated(messages, GenerationToolUpdate.RESULTS)
    }
    return messages
}

/** Update parts in place, preserving all message ids and non-tool content. */
private fun replaceGenerationTools(
    messages: List<UIMessage>,
    tools: List<UIMessagePart.Tool>,
): List<UIMessage> {
    val last = messages.last()
    return messages.dropLast(1) + last.copy(parts = last.parts.map { part ->
        if (part is UIMessagePart.Tool) tools.find { it.toolCallId == part.toolCallId } ?: part else part
    })
}

/** Preserve ordinary chat's auto/force/per-tool confirmation rules. */
internal fun prepareChatGenerationTools(
    calls: List<UIMessagePart.Tool>,
    definitions: List<Tool>,
    autoApproveAll: Boolean,
    forceConfirm: Boolean,
): GenerationToolApproval {
    var waiting = false
    val updated = calls.map { call ->
        val definition = definitions.find { it.name == call.toolName }
        when {
            autoApproveAll -> call
            (forceConfirm || definition?.needsApproval == true) && call.approvalState is ToolApprovalState.Auto -> {
                waiting = true
                call.copy(approvalState = ToolApprovalState.Pending)
            }
            call.approvalState is ToolApprovalState.Pending -> {
                waiting = true
                call
            }
            else -> call
        }
    }
    return GenerationToolApproval(updated, waiting)
}
