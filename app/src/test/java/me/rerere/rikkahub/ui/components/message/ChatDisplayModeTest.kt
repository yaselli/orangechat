package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.ChatDisplayMode
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ChatDisplayModeTest {
    @Test
    fun olderSettingsKeepStandardModeAndExistingAvatarPreferences() {
        val old =
            Json.decodeFromString<DisplaySetting>(
                """{"showUserAvatar":false,"showAssistantBubble":false}"""
            )
        assertEquals(ChatDisplayMode.STANDARD, old.chatDisplayMode)
        val everyday = old.copy(chatDisplayMode = ChatDisplayMode.EVERYDAY)
        assertFalse(everyday.showUserAvatar)
        assertFalse(everyday.showAssistantBubble)
        assertEquals(everyday, Json.decodeFromString<DisplaySetting>(Json.encodeToString(everyday)))
        assertEquals(old, everyday.copy(chatDisplayMode = ChatDisplayMode.STANDARD))
    }

    @Test
    fun everydayHidesProcessWithoutMutatingOrLosingOriginalSteps() {
        val thought = ThinkingStep.ReasoningStep(UIMessagePart.Reasoning("private thought"))
        val executed =
            ThinkingStep.ToolStep(
                UIMessagePart.Tool("a", "search_web", "{}", listOf(UIMessagePart.Text("result")))
            )
        val running = ThinkingStep.ToolStep(UIMessagePart.Tool("b", "search_web", "{}"))
        val original = listOf(thought, executed, running)
        assertEquals(emptyList<ThinkingStep>(), original.forChatDisplay(ChatDisplayMode.EVERYDAY))
        assertSame(original, original.forChatDisplay(ChatDisplayMode.STANDARD))
        assertEquals(3, original.size)
        assertEquals("result", (executed.tool.output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun pendingApprovalAndQuestionsStayReachableButCompletedOnesDisappear() {
        val approval =
            UIMessagePart.Tool(
                "a",
                "workspace_shell",
                "{}",
                approvalState = ToolApprovalState.Pending,
            )
        val question =
            UIMessagePart.Tool("b", "ask_user", "{}", approvalState = ToolApprovalState.Pending)
        val steps = listOf(ThinkingStep.ToolStep(approval), ThinkingStep.ToolStep(question))
        assertEquals(steps, steps.forChatDisplay(ChatDisplayMode.EVERYDAY))
        val completed = steps.map {
            ThinkingStep.ToolStep(it.tool.copy(output = listOf(UIMessagePart.Text("done"))))
        }
        assertEquals(emptyList<ThinkingStep>(), completed.forChatDisplay(ChatDisplayMode.EVERYDAY))
    }
}
