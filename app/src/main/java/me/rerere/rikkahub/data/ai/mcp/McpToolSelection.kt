package me.rerere.rikkahub.data.ai.mcp

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/** Use the caller's assistant and settings snapshot, including for background workflows. */
fun Settings.getAvailableMcpTools(assistant: Assistant): List<Pair<Uuid, McpTool>> = mcpServers
    .filter { it.commonOptions.enable && it.id in assistant.mcpServers }
    .flatMap { server ->
        server.commonOptions.tools.filter { it.enable }.map { server.id to it }
    }
