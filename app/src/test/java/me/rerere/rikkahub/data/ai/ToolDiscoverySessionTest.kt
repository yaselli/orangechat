package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolDiscoverySession
import me.rerere.rikkahub.data.ai.tools.withToolDiscoveryDirectory
import org.junit.Assert.*
import org.junit.Test

class ToolDiscoverySessionTest {
    private var executions = 0

    private fun tool(name: String, description: String = name, approval: Boolean = false) =
        Tool(
            name = name,
            description = description,
            needsApproval = approval,
            execute = {
                executions++
                listOf(UIMessagePart.Text("done"))
            },
        )

    private suspend fun invoke(entry: Tool, json: String): JsonObject =
        Json.parseToJsonElement(
                (entry.execute(Json.parseToJsonElement(json)).single() as UIMessagePart.Text).text
            )
            .jsonObject

    private fun entry(tools: List<Tool>) = tools.single { it.name == ToolDiscoverySession.ENTRY }

    private fun call(
        name: String,
        args: String = "{}",
        state: ToolApprovalState = ToolApprovalState.Auto,
    ) =
        UIMessagePart.Tool(
            toolCallId = "call-$name",
            toolName = name,
            input = args,
            approvalState = state,
        )

    @Test
    fun `catalog stays available without exposing schemas or executing tools`() = runBlocking {
        val hidden = tool("ssh_exec", "Remote server commands\n" + "detail ".repeat(1000))
        val resident = tool("memory_tool")
        val session = ToolDiscoverySession(setOf(resident.name))
        val exposed = session.expose(listOf(hidden, resident))
        assertEquals(listOf(resident.name, ToolDiscoverySession.ENTRY), exposed.map { it.name })
        val catalog = entry(exposed).systemPrompt(Model(), emptyList())
        assertTrue(catalog.contains("ssh_exec"))
        assertFalse(catalog.contains("detail ".repeat(100)))
        val found = invoke(entry(exposed), """{"action":"search","query":"帮我看看服务器日志"}""")
        assertEquals(
            "ssh_exec",
            found["matches"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content,
        )
        assertEquals(0, executions)
        assertFalse(session.expose(listOf(hidden, resident)).any { it.name == hidden.name })
    }

    @Test
    fun `load retains original schema approval and callback for this loop only`() = runBlocking {
        val hidden = tool("write_files", approval = true)
        val session = ToolDiscoverySession(emptySet())
        val before = session.expose(listOf(hidden))
        invoke(entry(before), """{"action":"load","names":["write_files"]}""")
        assertEquals(0, executions)
        val after = session.expose(listOf(hidden))
        assertSame(hidden, after.single { it.name == hidden.name })
        assertTrue(after.single { it.name == hidden.name }.needsApproval)
        assertTrue(session.expose(listOf(hidden)).contains(hidden))
        assertFalse(ToolDiscoverySession(emptySet()).expose(listOf(hidden)).contains(hidden))
    }

    @Test
    fun `unknown names and oversized batches cannot partially load unavailable tools`() =
        runBlocking {
            val hidden = tool("ssh_exec")
            val session = ToolDiscoverySession(emptySet())
            val entry = entry(session.expose(listOf(hidden)))
            val bad =
                invoke(entry, """{"action":"load","names":["ssh_exec","other_role_secret"]}""")
            assertFalse(bad["ok"]!!.jsonPrimitive.boolean)
            assertFalse(session.expose(listOf(hidden)).contains(hidden))
            val many = buildJsonObject {
                put("action", "load")
                put("names", buildJsonArray { repeat(9) { add("ssh_exec") } })
            }
            assertFalse(invoke(entry, many.toString())["ok"]!!.jsonPrimitive.boolean)
            assertEquals(0, executions)
        }

    @Test
    fun `same raw MCP names preserve origin and removal revokes loaded definition`() = runBlocking {
        val first = tool("mcp_00000001_list_games", "first game service")
        val second = tool("mcp_00000002_list_games", "second game service")
        val session = ToolDiscoverySession(emptySet())
        val entry = entry(session.expose(listOf(first, second)))
        assertFalse(
            invoke(entry, """{"action":"load","names":["list_games"]}""")["ok"]!!
                .jsonPrimitive
                .boolean
        )
        invoke(entry, """{"action":"load","names":["mcp_00000002_list_games"]}""")
        assertEquals(
            listOf(ToolDiscoverySession.ENTRY, second.name),
            session.expose(listOf(first, second)).map { it.name },
        )
        assertFalse(session.expose(listOf(first)).contains(second))
        assertEquals(0, executions)
    }

    @Test
    fun `directory is sorted stable and provider decoration leaves stored history unchanged`() {
        val a = tool("a", "purpose\nnext line")
        val z = tool("z")
        val session = ToolDiscoverySession(emptySet())
        val first = entry(session.expose(listOf(z, a))).systemPrompt(Model(), emptyList())
        val second = entry(session.expose(listOf(a, z))).systemPrompt(Model(), emptyList())
        assertEquals(first, second)
        val history = listOf(UIMessage.system("persona"), UIMessage.user("hi"))
        val decorated = history.withToolDiscoveryDirectory(session.expose(listOf(a, z)), Model())
        assertEquals("persona", history.first().toText())
        assertEquals(history.map { it.id }, decorated.map { it.id })
        assertTrue(decorated.first().toText().contains(first))
        assertEquals(
            MessageRole.SYSTEM,
            listOf(history.last())
                .withToolDiscoveryDirectory(
                    session.expose(listOf(a, z)),
                    Model(),
                )
                .first()
                .role,
        )
    }

    @Test
    fun `model loop loads a tool then receives its definition before execution and followup`() =
        runBlocking {
            val target = tool("ssh_exec")
            val requests = mutableListOf<List<String>>()
            val replies =
                ArrayDeque(
                    listOf(
                        call(
                            ToolDiscoverySession.ENTRY,
                            """{"action":"load","names":["ssh_exec"]}""",
                        ),
                        call(target.name),
                    )
                )
            val result =
                runGenerationToolLoop(
                    initialMessages = listOf(UIMessage.user("帮我看看服务器日志")),
                    maxSteps = 4,
                    discovery = ToolDiscoverySession(emptySet()),
                    toolsForStep = { listOf(target) },
                    generateTurn = { messages, definitions, _ ->
                        requests += definitions.map { it.name }
                        messages +
                            if (replies.isEmpty()) UIMessage.assistant("已检查")
                            else
                                UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(replies.removeFirst()),
                                )
                    },
                    executeTool = { call, definition, _ ->
                        executeChatGenerationTool(call, definition, Json)
                    },
                    onToolsUpdated = { _, _ -> },
                )
            assertEquals(listOf(ToolDiscoverySession.ENTRY), requests[0])
            assertTrue(requests[1].contains(target.name))
            assertTrue(requests[2].contains(target.name))
            assertEquals(1, executions)
            assertEquals("已检查", result.last().toText())
        }

    @Test
    fun `approval resume rehydrates still available definition without another model load`() =
        runBlocking {
            val target = tool("write_files", approval = true)
            val initial =
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(call(target.name, state = ToolApprovalState.Approved)),
                )
            val result =
                runGenerationToolLoop(
                    initialMessages = listOf(UIMessage.user("写文件"), initial),
                    maxSteps = 3,
                    resumeApprovedTools = true,
                    discovery = ToolDiscoverySession(emptySet()),
                    toolsForStep = { listOf(target) },
                    prepareTools = { calls, definitions ->
                        prepareChatGenerationTools(calls, definitions, false, false)
                    },
                    generateTurn = { messages, _, _ ->
                        assertEquals(1, executions)
                        messages + UIMessage.assistant("done")
                    },
                    executeTool = { call, definition, _ ->
                        executeChatGenerationTool(call, definition, Json)
                    },
                    onToolsUpdated = { _, _ -> },
                )
            assertEquals(1, executions)
            assertEquals("done", result.last().toText())
        }

    @Test
    fun `hidden tool cannot execute in same batch as loading it`() = runBlocking {
        val target = tool("ssh_exec", approval = true)
        val result =
            runGenerationToolLoop(
                initialMessages = listOf(UIMessage.user("check server")),
                maxSteps = 2,
                discovery = ToolDiscoverySession(emptySet()),
                toolsForStep = { listOf(target) },
                generateTurn = { messages, _, step ->
                    messages +
                        if (step == 0)
                            UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts =
                                    listOf(
                                        call(
                                            ToolDiscoverySession.ENTRY,
                                            """{"action":"load","names":["ssh_exec"]}""",
                                        ),
                                        call("ssh_exec"),
                                    ),
                            )
                        else UIMessage.assistant("done")
                },
                executeTool = { call, definition, _ ->
                    executeGenerationTool(call, definition, Json)
                },
                onToolsUpdated = { _, _ -> },
            )
        assertEquals(0, executions)
        assertTrue(
            result[result.lastIndex - 1].getTools().last().output.toString().contains("not found")
        )
    }

    @Test
    fun `loading temporary tools leaves resident definitions entry and directory prefix unchanged`() =
        runBlocking {
            val hidden = tool("a_hidden")
            val resident = tool("z_resident")
            val session = ToolDiscoverySession(setOf(resident.name))
            val first = session.expose(listOf(hidden, resident))
            val initialDefinitions = toolDefinitionSizes(first)
            val initialDirectory = entry(first).systemPrompt(Model(), emptyList())
            invoke(entry(first), """{"action":"load","names":["a_hidden"]}""")
            val loaded = session.expose(listOf(hidden, resident))
            assertEquals(
                listOf(resident.name, ToolDiscoverySession.ENTRY, hidden.name),
                loaded.map { it.name },
            )
            assertEquals(initialDefinitions, toolDefinitionSizes(loaded.take(first.size)))
            assertEquals(initialDirectory, entry(loaded).systemPrompt(Model(), emptyList()))
            assertEquals(1, session.profile(0).loadedCount)
        }
}
