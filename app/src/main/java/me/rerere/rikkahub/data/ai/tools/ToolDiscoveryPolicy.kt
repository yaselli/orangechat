package me.rerere.rikkahub.data.ai.tools

import kotlin.uuid.Uuid
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.getAvailableMcpTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant

/** Exposure only: never registers a tool, enables an MCP, or changes execution permissions. */
object ToolDiscoveryPolicy {
    private val residentBuiltins =
        setOf(
            "memory_tool",
            "avatar_interaction",
            "message_reaction",
            "shared_music",
            "search_web",
            "scrape_web",
            "web_fetch",
            "get_time_info",
            "set_alarm",
            "set_timer",
            "calendar_tool",
            "text_to_speech",
            "ask_user",
            "clipboard_tool",
        )
    private val residentPluginTools =
        setOf(
            "list_stickers",
            "send_sticker",
            "get_weather",
            "get_weather_brief",
            "get_weather_forecast",
        )
    private val pluginName = Regex("^plg_[0-9a-f]{8}_(.+)$")

    fun residentServers(assistant: Assistant, servers: List<McpServerConfig>): Set<Uuid> =
        assistant.toolDiscoveryMcpServers
            ?: servers
                .filter { server ->
                    // Recognize the external memory interface, independent of user-assigned server
                    // names.
                    server.commonOptions.tools
                        .map { it.name }
                        .containsAll(listOf("read_memory", "recall_memory", "propose_memory"))
                }
                .map { it.id }
                .toSet()

    fun residentNames(
        assistant: Assistant,
        settings: Settings,
        available: List<Tool>,
    ): Set<String> {
        val servers = residentServers(assistant, settings.mcpServers)
        val mcp =
            settings
                .getAvailableMcpTools(assistant)
                .filter { it.first in servers }
                .map { (server, tool) -> ToolNaming.buildMcpToolName(server, tool.name) }
                .toSet()
        return residentBuiltins +
            available
                .filter { tool ->
                    tool.name in mcp ||
                        pluginName.matchEntire(tool.name)?.groupValues?.get(1) in
                            residentPluginTools
                }
                .map { it.name }
                .toSet()
    }
}
