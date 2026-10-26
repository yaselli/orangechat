package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.tools.ToolDiscoverySession
import org.junit.Assert.*
import org.junit.Test

class ToolDiscoverySizeTest {
    @Test
    fun `size accounting includes directory and actual entry without exposing hidden schemas`() {
        // Reproducible synthetic fixture, not a measurement of the user's private MCP definitions.
        val tools =
            (1..141).map { index ->
                Tool(
                    name = "tool_$index",
                    description = "Tool $index purpose. " + "Detailed usage rule. ".repeat(15),
                    parameters = {
                        InputSchema.Obj(
                            buildJsonObject {
                                put(
                                    "payload",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Detailed parameter rule. ".repeat(20))
                                    },
                                )
                            }
                        )
                    },
                    execute = { error("Size accounting must not execute tools") },
                )
            }
        val session = ToolDiscoverySession(tools.take(37).map { it.name }.toSet())
        val exposed = session.expose(tools)
        val full = toolDefinitionSizes(tools).sumOf { it.bytes }
        val actual = toolDefinitionSizes(exposed).sumOf { it.bytes }
        val profile = session.profile(full)
        assertEquals(141, profile.availableCount)
        assertEquals(37, profile.residentCount)
        assertEquals(0, profile.loadedCount)
        assertEquals(38, exposed.size)
        assertEquals(full, profile.fullSchemaBytes)
        val directory = exposed.last().systemPrompt(Model(), emptyList())
        assertEquals(inputUtf8Bytes(directory), profile.directoryBytes)
        assertTrue(actual + profile.directoryBytes < full)
        assertFalse(directory.contains("Detailed parameter rule"))
        val message =
            UIMessage.assistant("done")
                .attachRequestInputProfile(
                    RequestInputProfile(
                        messageCount = 7,
                        textBytes = profile.directoryBytes,
                        roles = emptyList(),
                        systemParts = emptyList(),
                        transformations = emptyList(),
                        recallBytes = 0,
                        toolCount = exposed.size,
                        toolSchemaBytes = actual,
                        largestTools =
                            toolDefinitionSizes(exposed).sortedByDescending { it.bytes }.take(10),
                        customBodyKeys = 0,
                        discovery = profile,
                    )
                )
        assertEquals(profile, message.requestInputProfile()!!.discovery)
        assertEquals(actual, message.requestInputProfile()!!.toolSchemaBytes)
    }
}
