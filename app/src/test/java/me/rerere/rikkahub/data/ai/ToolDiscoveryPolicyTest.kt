package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.rikkahub.data.ai.mcp.*
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class ToolDiscoveryPolicyTest {
    private fun tool(name: String) =
        Tool(name = name, description = name, execute = { error("must not execute") })

    private val ob =
        McpServerConfig.StreamableHTTPServer(
            commonOptions =
                McpCommonOptions(
                    name = "renamed external memory",
                    tools =
                        listOf(
                            McpTool(name = "read_memory"),
                            McpTool(name = "recall_memory"),
                            McpTool(name = "propose_memory"),
                            McpTool(name = "write_diary"),
                            McpTool(name = "disabled", enable = false),
                        ),
                )
        )
    private val game =
        McpServerConfig.StreamableHTTPServer(
            commonOptions =
                McpCommonOptions(
                    name = "OB-like name alone does not pin",
                    tools = listOf(McpTool(name = "list_games")),
                )
        )
    private val assistant =
        Assistant(toolDiscoveryEnabled = true, mcpServers = setOf(ob.id, game.id))
    private val settings = Settings(mcpServers = listOf(ob, game))
    private val available =
        settings.getAvailableMcpTools(assistant).map { (server, t) ->
            tool(ToolNaming.buildMcpToolName(server, t.name))
        }

    @Test
    fun `OB defaults pin the whole interface but never disabled tools or another server`() {
        val names = ToolDiscoveryPolicy.residentNames(assistant, settings, available)
        assertTrue(names.contains(ToolNaming.buildMcpToolName(ob.id, "write_diary")))
        assertFalse(names.contains(ToolNaming.buildMcpToolName(ob.id, "disabled")))
        assertFalse(names.contains(ToolNaming.buildMcpToolName(game.id, "list_games")))
        assertEquals(
            setOf(ob.id),
            ToolDiscoveryPolicy.residentServers(assistant, settings.mcpServers),
        )
    }

    @Test
    fun `explicit MCP pin choices override automatic defaults including an empty choice`() {
        assertEquals(
            emptySet<kotlin.uuid.Uuid>(),
            ToolDiscoveryPolicy.residentServers(
                assistant.copy(toolDiscoveryMcpServers = emptySet()),
                settings.mcpServers,
            ),
        )
        val names =
            ToolDiscoveryPolicy.residentNames(
                assistant.copy(toolDiscoveryMcpServers = setOf(game.id)),
                settings,
                available,
            )
        assertFalse(names.contains(ToolNaming.buildMcpToolName(ob.id, "read_memory")))
        assertTrue(names.contains(ToolNaming.buildMcpToolName(game.id, "list_games")))
    }

    @Test
    fun `unselected and disabled MCP cannot be exposed even if pinned`() {
        val disabled = ob.copy(commonOptions = ob.commonOptions.copy(enable = false))
        for (a in listOf(assistant.copy(mcpServers = emptySet()), assistant)) {
            val snapshot = settings.copy(mcpServers = listOf(disabled))
            val names = ToolDiscoveryPolicy.residentNames(a, snapshot, emptyList())
            assertFalse(names.any { it.startsWith("mcp_") })
        }
    }

    @Test
    fun `resident plugin tools match the namespace without pinning unrelated MCP names`() {
        val sticker = ToolNaming.buildPluginToolName("different.sticker.plugin", "send_sticker")
        val weather =
            ToolNaming.buildPluginToolName("custom.weather.plugin", "get_weather_forecast")
        val mcp = ToolNaming.buildMcpToolName(game.id, "send_sticker")
        val developer = ToolNaming.buildPluginToolName("dev.guide", "get_plugin_guide")
        val names =
            ToolDiscoveryPolicy.residentNames(
                assistant,
                settings,
                listOf(sticker, weather, mcp, developer).map(::tool),
            )
        assertTrue(sticker in names)
        assertTrue(weather in names)
        assertFalse(mcp in names)
        assertFalse(developer in names)
    }

    @Test
    fun `agreed resident builtins remain available while telephone files and workflows defer`() {
        val all =
            listOf(
                "memory_tool",
                "avatar_interaction",
                "message_reaction",
                "shared_music",
                "search_web",
                "web_fetch",
                "scrape_web",
                "get_time_info",
                "set_alarm",
                "set_timer",
                "calendar_tool",
                "text_to_speech",
                "ask_user",
                "clipboard_tool",
                "request_voice_call",
                "write_files",
                "workflow_create",
                "ssh_exec",
            )
        val names = ToolDiscoveryPolicy.residentNames(assistant, settings, all.map(::tool))
        assertTrue(all.take(14).all { it in names })
        assertTrue(all.drop(14).none { it in names })
        val exposed = ToolDiscoverySession(names).expose(all.map(::tool))
        assertEquals(15, exposed.size) // 14 resident definitions and the discover/load entry.
    }

    @Test
    fun `old profile defaults to full mode and opt in is ignored for a model without tools`() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString<Assistant>("{}")
        assertFalse(old.toolDiscoveryEnabled)
        assertNull(old.toolDiscoveryMcpServers)
        val model = Model(abilities = listOf(ModelAbility.TOOL))
        assertNull(createToolDiscoverySession(old, settings, model, available))
        assertNull(createToolDiscoverySession(assistant, settings, Model(), available))
        assertNotNull(createToolDiscoverySession(assistant, settings, model, available))
        assertNull(
            createToolDiscoverySession(
                assistant.copy(toolDiscoveryEnabled = false),
                settings,
                model,
                available,
            )
        )
    }

    @Test
    fun `custom provider bodies retain precedence by falling back to full exposure`() {
        val model = Model(abilities = listOf(ModelAbility.TOOL))
        assertNull(
            createToolDiscoverySession(
                assistant.copy(customBodies = listOf(CustomBody("tools", JsonArray(emptyList())))),
                settings,
                model,
                available,
            )
        )
        assertNull(
            createToolDiscoverySession(
                assistant,
                settings,
                model.copy(customBodies = listOf(CustomBody("messages", JsonArray(emptyList())))),
                available,
            )
        )
        assertNotNull(
            createToolDiscoverySession(
                assistant.copy(
                    customBodies = listOf(CustomBody("temperature", JsonPrimitive(0.8)))
                ),
                settings,
                model,
                available,
            )
        )
    }

    @Test
    fun `MCP pin choices and opt in round trip without altering existing assistant settings`() {
        val json = Json { encodeDefaults = true }
        val original =
            assistant.copy(
                toolDiscoveryMcpServers = setOf(ob.id),
                systemPrompt = "persona",
                enableMemory = true,
            )
        val decoded =
            json.decodeFromString<Assistant>(json.encodeToString(Assistant.serializer(), original))
        assertEquals(original, decoded)
    }
}
