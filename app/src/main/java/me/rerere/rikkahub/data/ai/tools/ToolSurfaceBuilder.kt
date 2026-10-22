/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.getAvailableMcpTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.plugin.provider.PluginToolProvider

/**
 * Builds the full tool surface for an assistant: search + local + system + workspace + skill
 * + MCP + plugin tools. This is the single source of truth shared by [me.rerere.rikkahub.service.ChatService]
 * (interactive) and [me.rerere.rikkahub.workflow.execution.WorkflowEngine] (headless fire), so a
 * workflow action can reference any tool the assistant actually has registered - not just the
 * local-tool subset. Without this, workflow_create would reject system/MCP/plugin tool names as
 * "unknown_tool" and the engine couldn't execute them at fire time.
 *
 * Workflow fire passes empty history and a headless invocation context. Chat passes its current
 * history, working directory and exposure policy, including proactive AppUsage consent.
 */
class ToolSurfaceBuilder(
    private val context: Context,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val pluginToolProvider: PluginToolProvider,
    private val workspaceRepository: WorkspaceRepository,
    private val json: Json,
    private val memoryRepository: MemoryRepository,
) {
    suspend fun build(
        assistant: me.rerere.rikkahub.data.model.Assistant,
        settings: Settings,
        invocationContext: ToolInvocationContext,
        recentMessages: List<UIMessage> = emptyList(),
        workspaceCwd: String? = null,
        policy: ToolSurfacePolicy = ToolSurfacePolicy(),
    ): List<Tool> = buildList {
        // Memory tools - mirror GenerationHandler: only when the assistant has memory enabled.
        if (policy.includeMemoryTools && assistant.enableMemory) {
            val memoryAssistantId = if (assistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistant.id.toString()
            }
            val source = MemorySource(
                kind = "tool",
                conversationId = invocationContext.callerConversationId.orEmpty(),
                messageId = recentMessages.lastOrNull { it.role == MessageRole.USER }?.id?.toString().orEmpty(),
            )
            addAll(buildMemoryTools(
                json = json,
                onCreation = { content -> memoryRepository.addMemory(memoryAssistantId, content, source) },
                onUpdate = { id, content -> memoryRepository.updateContent(id, content, source, memoryAssistantId) },
                onRead = { id -> memoryRepository.getMemoryInScope(id, memoryAssistantId) },
                onDelete = { id -> memoryRepository.deleteMemory(id, memoryAssistantId) },
            ))
        }
        if (settings.enableWebSearch) {
            addAll(createSearchTools(settings))
        }
        addAll(localTools.getTools(policy.localOptions(assistant.localTools), invocationContext))
        val systemToolsOptions = policy.systemOptions(settings.systemToolsSetting.getEnabledOptions())
        if (systemToolsOptions.isNotEmpty()) {
            addAll(SystemTools(context, settings).getTools(systemToolsOptions, recentMessages, filesManager))
        }
        val workspaceId = assistant.workspaceId?.toString()
        val shellStatus = if (policy.requireReadyWorkspace && workspaceId != null) {
            workspaceRepository.getById(workspaceId)?.shellStatus
        } else null
        if (policy.workspaceAllowed(workspaceId, shellStatus)) {
            addAll(createWorkspaceTools(workspaceId, workspaceRepository, workspaceCwd))
        }
        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(createSkillTools(assistant.enabledSkills, skillManager.listSkills(), skillManager))
        }
        settings.getAvailableMcpTools(assistant).forEach { (serverId, tool) ->
            add(
                Tool(
                    name = ToolNaming.buildMcpToolName(serverId, tool.name),
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = tool.needsApproval,
                    execute = {
                        mcpManager.callTool(serverId, tool.name, it.jsonObject)
                    },
                )
            )
        }
        addAll(pluginToolProvider.getTools())
    }
}
