package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class McpToolSelectionTest {
    private val music = McpServerConfig.SseTransportServer(
        commonOptions = McpCommonOptions(tools = listOf(McpTool(name = "play_music"))),
    )
    private val weather = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(tools = listOf(McpTool(name = "weather"))),
    )
    private val author = Assistant(mcpServers = setOf(music.id))
    private val current = Assistant(mcpServers = setOf(weather.id))
    private val settings = Settings(
        assistantId = current.id,
        assistants = listOf(author, current),
        mcpServers = listOf(music, weather),
    )

    @Test
    fun `workflow author keeps their tools while a different assistant is selected`() {
        assertEquals(listOf(music.id to music.commonOptions.tools.single()), settings.getAvailableMcpTools(author))
        assertEquals(listOf(weather.id to weather.commonOptions.tools.single()), settings.getAvailableMcpTools(current))
        assertEquals(
            settings.getAvailableMcpTools(author),
            settings.copy(assistantId = author.id).getAvailableMcpTools(author),
        )
    }

    @Test
    fun `assistant without MCP does not inherit tools from the selected assistant`() {
        assertTrue(settings.getAvailableMcpTools(author.copy(mcpServers = emptySet())).isEmpty())
        assertTrue(settings.getAvailableMcpTools(author.copy(mcpServers = setOf(Uuid.random()))).isEmpty())
    }

    @Test
    fun `disabled servers and tools remain unavailable`() {
        val enabled = McpTool(name = "enabled")
        val disabled = McpTool(name = "disabled", enable = false)
        val server = music.copy(commonOptions = McpCommonOptions(tools = listOf(enabled, disabled)))
        assertEquals(
            listOf(music.id to enabled),
            settings.copy(mcpServers = listOf(server)).getAvailableMcpTools(author),
        )
        assertTrue(settings.copy(mcpServers = listOf(
            server.copy(commonOptions = server.commonOptions.copy(enable = false)),
        )).getAvailableMcpTools(author).isEmpty())
    }

    @Test
    fun `selection uses the supplied settings snapshot`() {
        val snapshot = settings.copy(mcpServers = listOf(music))
        val changed = settings.copy(mcpServers = emptyList())
        assertTrue(changed.getAvailableMcpTools(author).isEmpty())
        assertEquals(listOf(music.id to music.commonOptions.tools.single()), snapshot.getAvailableMcpTools(author))
    }

    @Test
    fun `shared servers keep schema description and approval requirements`() {
        val schema = InputSchema.Obj(properties = buildJsonObject {
            put("query", buildJsonObject { put("type", "string") })
        })
        val tool = McpTool(name = "search", description = "Search", inputSchema = schema, needsApproval = true)
        val shared = music.copy(commonOptions = McpCommonOptions(tools = listOf(tool)))
        val snapshot = settings.copy(mcpServers = listOf(shared))
        for (assistant in listOf(author, current.copy(mcpServers = setOf(shared.id)))) {
            val selected = snapshot.getAvailableMcpTools(assistant).single()
            assertEquals(shared.id, selected.first)
            assertSame(tool, selected.second)
            assertSame(schema, selected.second.inputSchema)
            assertTrue(selected.second.needsApproval)
        }
    }
}
