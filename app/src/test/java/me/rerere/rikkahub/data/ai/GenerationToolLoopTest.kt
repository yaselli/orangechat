package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class GenerationToolLoopTest {
    @Test
    fun `multiple model and tool rounds return results before the next request`() = runBlocking {
        val first = reply(call("one"))
        val second = reply(call("two"))
        val final = UIMessage.assistant("done")
        val f = Fixture(listOf(first, second, final))
        val result = f.run()
        assertEquals(listOf("one", "two"), f.executed)
        assertEquals(3, f.requests.size)
        assertEquals("one", f.requests[1].last().getTools().single().toolCallId)
        assertTrue(f.requests[1].last().getTools().single().isExecuted)
        assertEquals("two", f.requests[2].last().getTools().single().toolCallId)
        assertTrue(f.requests[2].last().getTools().single().isExecuted)
        assertEquals(listOf(f.history.single().id, first.id, second.id, final.id), result.map { it.id })
        assertEquals("done", result.last().toText())
        assertEquals(listOf(GenerationToolUpdate.RESULTS, GenerationToolUpdate.RESULTS), f.updates.map { it.second })
    }

    @Test
    fun `mixed automatic and sensitive tools pause together before either executes`() = runBlocking {
        val f = Fixture(listOf(reply(call("auto"), call("sensitive", "sensitive"))))
        val result = f.run(chatPolicy = true, autoApprove = false)
        assertTrue(f.executed.isEmpty())
        assertEquals(1, f.requests.size)
        assertEquals(ToolApprovalState.Pending, result.last().getTools()[1].approvalState)
        assertEquals(ToolApprovalState.Auto, result.last().getTools()[0].approvalState)
        assertEquals(GenerationToolUpdate.APPROVAL, f.updates.single().second)
    }

    @Test
    fun `approved denied and answered calls resume without regenerating or rerunning completed tools`() = runBlocking {
        val completed = call("completed").copy(output = listOf(UIMessagePart.Text("already done")))
        val initial = reply(
            call("approved").copy(approvalState = ToolApprovalState.Approved),
            call("denied").copy(approvalState = ToolApprovalState.Denied("no")),
            call("answered").copy(approvalState = ToolApprovalState.Answered("yes")),
            completed,
        )
        val f = Fixture(listOf(UIMessage.assistant("done")))
        val result = f.run(initial = f.history + initial, chatPolicy = true, resume = true)
        assertEquals(listOf("approved"), f.executed)
        assertEquals(1, f.requests.size)
        val resumed = f.requests.single().last().getTools()
        assertTrue(resumed.all { it.isExecuted })
        assertEquals("yes", (resumed[2].output.single() as UIMessagePart.Text).text)
        assertTrue((resumed[1].output.single() as UIMessagePart.Text).text.contains("denied by user"))
        assertEquals(completed, resumed[3])
        assertEquals(initial.id, f.requests.single().last().id)
        assertEquals("done", result.last().toText())
    }

    @Test
    fun `pending resumed input with no approval remains unexecuted`() = runBlocking {
        val initial = reply(call("pending").copy(approvalState = ToolApprovalState.Pending))
        val f = Fixture(emptyList())
        val result = f.run(initial = f.history + initial, chatPolicy = true, autoApprove = false, resume = true)
        assertTrue(f.executed.isEmpty())
        assertEquals(ToolApprovalState.Pending, result.last().getTools().single().approvalState)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun `mixed batch resumes automatic calls with each user decision before next model request`() = runBlocking {
        for (decision in listOf(
            ToolApprovalState.Approved,
            ToolApprovalState.Denied("no"),
            ToolApprovalState.Answered("yes"),
        )) {
            val completed = call("completed").copy(output = listOf(UIMessagePart.Text("already done")))
            val initial = reply(call("auto"), call("sensitive", "sensitive"), completed)
            val paused = Fixture(listOf(initial))
            val waiting = paused.run(chatPolicy = true, autoApprove = false)
            assertTrue(paused.executed.isEmpty())
            val approved = waiting.dropLast(1) + waiting.last().copy(parts = waiting.last().parts.map { part ->
                if (part is UIMessagePart.Tool && part.toolCallId == "sensitive") {
                    part.copy(approvalState = decision)
                } else part
            })
            val resumed = Fixture(listOf(UIMessage.assistant("done")))
            val result = resumed.run(initial = approved, chatPolicy = true, autoApprove = false, resume = true)
            assertEquals(
                if (decision == ToolApprovalState.Approved) listOf("auto", "sensitive") else listOf("auto"),
                resumed.executed,
            )
            val sent = resumed.requests.single().last()
            assertEquals(initial.id, sent.id)
            assertTrue(sent.getTools().all { it.isExecuted })
            assertEquals(completed, sent.getTools().last())
            assertEquals(decision, sent.getTools()[1].approvalState)
            when (decision) {
                is ToolApprovalState.Denied -> assertTrue(sent.getTools()[1].output.single().let {
                    (it as UIMessagePart.Text).text.contains("denied by user")
                })
                is ToolApprovalState.Answered -> assertEquals("yes", (sent.getTools()[1].output.single()
                    as UIMessagePart.Text).text)
                else -> Unit
            }
            assertEquals("done", result.last().toText())
        }
    }

    @Test
    fun `partly approved batch still waits without executing automatic or approved calls`() = runBlocking {
        val initial = reply(
            call("auto"),
            call("approved", "sensitive").copy(approvalState = ToolApprovalState.Approved),
            call("pending", "sensitive").copy(approvalState = ToolApprovalState.Pending),
        )
        val f = Fixture(emptyList())
        val history = f.history + initial
        assertEquals(history, f.run(initial = history, chatPolicy = true, autoApprove = false, resume = true))
        assertTrue(f.requests.isEmpty())
        assertTrue(f.executed.isEmpty())
        assertTrue(f.updates.isEmpty())
    }

    @Test
    fun `resumed automatic sensitive call still requires approval`() = runBlocking {
        val initial = reply(call("auto"), call("sensitive", "sensitive"))
        val f = Fixture(emptyList())
        val result = f.run(initial = f.history + initial, chatPolicy = true, autoApprove = false, resume = true)
        assertEquals(ToolApprovalState.Pending, result.last().getTools()[1].approvalState)
        assertTrue(f.requests.isEmpty())
        assertTrue(f.executed.isEmpty())
        assertEquals(GenerationToolUpdate.APPROVAL, f.updates.single().second)
    }

    @Test
    fun `unfinished automatic batch resumes without an extra model request first`() = runBlocking {
        val f = Fixture(listOf(UIMessage.assistant("done")))
        f.run(initial = f.history + reply(call("auto")), chatPolicy = true, autoApprove = false, resume = true)
        assertEquals(listOf("auto"), f.executed)
        assertTrue(f.requests.single().last().getTools().single().isExecuted)
    }

    @Test
    fun `global force confirmation and auto approval keep their precedence`() {
        val calls = listOf(call("one"))
        val definitions = listOf(Tool("example", "", execute = { emptyList() }))
        val forced = prepareChatGenerationTools(calls, definitions, false, true)
        assertTrue(forced.waitingForApproval)
        assertEquals(ToolApprovalState.Pending, forced.calls.single().approvalState)
        val automatic = prepareChatGenerationTools(calls, definitions, true, true)
        assertFalse(automatic.waitingForApproval)
        assertEquals(calls, automatic.calls)
    }

    @Test
    fun `proactive budget leaves one final model turn with all tool results`() = runBlocking {
        val f = Fixture(listOf(reply(call("one")), reply(call("two")), UIMessage.assistant("done")))
        val result = f.run(maxSteps = 2, finalTurn = true)
        assertEquals(listOf("one", "two"), f.executed)
        assertEquals(listOf(false, false, true), f.steps.map { it.second })
        assertEquals(3, f.requests.size)
        assertTrue(f.requests.last().last().getTools().all { it.isExecuted })
        assertEquals("done", result.last().toText())
    }

    @Test
    fun `tool request on final proactive turn fails without executing it`() = runBlocking {
        val f = Fixture(listOf(reply(call("one")), reply(call("over-budget"))))
        try {
            f.run(maxSteps = 1, finalTurn = true)
            fail("Final turn must not execute another tool")
        } catch (error: IllegalStateException) {
            assertEquals("Proactive tool limit reached before a final reply", error.message)
        }
        assertEquals(listOf("one"), f.executed)
        assertEquals(2, f.requests.size)
        assertEquals(1, f.updates.size)
    }

    @Test
    fun `ordinary chat keeps its iteration limit including approval resumption`() = runBlocking {
        val f = Fixture(listOf(UIMessage.assistant("must not be requested")))
        val initial = reply(call("approved").copy(approvalState = ToolApprovalState.Approved))
        val result = f.run(initial = f.history + initial, maxSteps = 1, chatPolicy = true, resume = true)
        assertTrue(f.requests.isEmpty())
        assertEquals(listOf("approved"), f.executed)
        assertTrue(result.last().getTools().single().isExecuted)
    }

    @Test
    fun `zero ordinary steps leave history untouched`() = runBlocking {
        val f = Fixture(emptyList())
        assertSame(f.history, f.run(maxSteps = 0))
        assertTrue(f.requests.isEmpty())
        assertTrue(f.updates.isEmpty())
    }

    @Test
    fun `proactive control replies complete without forced extra requests`() = runBlocking {
        for (control in listOf("[PASS]", "[WAIT:20]", "[STOP]")) {
            val f = Fixture(listOf(UIMessage.assistant(control)))
            assertEquals(control, f.run(maxSteps = 0, finalTurn = true).last().toText())
            assertEquals(1, f.requests.size)
            assertTrue(f.executed.isEmpty())
        }
    }

    @Test
    fun `failed or missing tools return paired results and continue to a reply`() = runBlocking {
        val f = Fixture(listOf(reply(call("failed", "broken"), call("missing", "absent")), UIMessage.assistant("done")))
        val result = f.run()
        val results = f.requests.last().last().getTools()
        assertEquals(listOf("failed", "missing"), results.map { it.toolCallId })
        assertTrue(results.all { it.isExecuted })
        assertEquals("done", result.last().toText())
    }

    @Test
    fun `already executed tools in a model response are not executed again`() = runBlocking {
        val completed = call("old").copy(output = listOf(UIMessagePart.Text("result")))
        val f = Fixture(listOf(reply(completed, UIMessagePart.Text("done"))))
        val result = f.run()
        assertEquals(completed, result.last().getTools().single())
        assertTrue(f.executed.isEmpty())
        assertEquals(1, f.requests.size)
    }

    @Test
    fun `cancel first tool stops remaining tools and further model calls`() = runBlocking {
        val cancellation = CancellationException("stop")
        val f = Fixture(listOf(reply(call("cancel", "cancel"), call("later"))))
        f.definitions += Tool("cancel", "", execute = { throw cancellation })
        try {
            f.run()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertTrue(f.executed.isEmpty())
        assertEquals(1, f.requests.size)
        assertTrue(f.updates.isEmpty())
    }

    @Test
    fun `model failure is propagated without retries or history mutation`() = runBlocking {
        val failure = IllegalStateException("API failed")
        val f = Fixture(emptyList(), failure)
        try {
            f.run()
            fail("API failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, f.requests.size)
        assertEquals(f.history, f.requests.single())
        assertTrue(f.executed.isEmpty())
        assertTrue(f.updates.isEmpty())
    }

    @Test
    fun `persistence failure prevents continuing generation`() = runBlocking {
        val failure = IllegalStateException("save failed")
        val f = Fixture(listOf(reply(call("one")), UIMessage.assistant("must not be requested")))
        try {
            f.run(onUpdate = { throw failure })
            fail("Persistence failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, f.requests.size)
        assertEquals(listOf("one"), f.executed)
    }

    private fun call(id: String, name: String = "example") = UIMessagePart.Tool(id, name, "{\"id\":\"$id\"}")
    private fun reply(vararg parts: UIMessagePart) = UIMessage.assistant("").copy(parts = parts.toList())

    private class Fixture(responses: List<UIMessage>, private val modelFailure: Exception? = null) {
        val history = listOf(UIMessage.user("question"))
        val requests = mutableListOf<List<UIMessage>>()
        val executed = mutableListOf<String>()
        val updates = mutableListOf<Pair<List<UIMessage>, GenerationToolUpdate>>()
        val steps = mutableListOf<Pair<Int, Boolean>>()
        private val replies = responses.toMutableList()
        val definitions = mutableListOf(
            Tool("example", "", execute = { args ->
                val id = args.jsonObject.getValue("id").jsonPrimitive.content
                executed.add(id)
                listOf(UIMessagePart.Text("done"))
            }),
            Tool("sensitive", "", needsApproval = true, execute = {
                executed.add("sensitive")
                listOf(UIMessagePart.Text("done"))
            }),
            Tool("broken", "", execute = { throw IllegalStateException("broken") }),
        )

        suspend fun run(
            initial: List<UIMessage> = history,
            maxSteps: Int = 10,
            finalTurn: Boolean = false,
            resume: Boolean = false,
            chatPolicy: Boolean = false,
            autoApprove: Boolean = true,
            onUpdate: suspend () -> Unit = {},
        ) = runGenerationToolLoop(
            initialMessages = initial,
            maxSteps = maxSteps,
            reserveFinalModelTurn = finalTurn,
            resumeApprovedTools = resume,
            toolLimitError = "Proactive tool limit reached before a final reply",
            toolsForStep = { definitions },
            generateTurn = { messages, _, _ ->
                requests.add(messages)
                modelFailure?.let { throw it }
                messages + replies.removeAt(0)
            },
            prepareTools = { calls, tools ->
                if (chatPolicy) prepareChatGenerationTools(calls, tools, autoApprove, false)
                else GenerationToolApproval(calls)
            },
            executeTool = { call, definition, _ ->
                if (chatPolicy) executeChatGenerationTool(call, definition, Json)
                else executeGenerationTool(call, definition, Json)
            },
            onToolsUpdated = { messages, update ->
                updates.add(messages to update)
                onUpdate()
            },
            onStep = { step, isFinal -> steps.add(step to isFinal) },
        )
    }
}
