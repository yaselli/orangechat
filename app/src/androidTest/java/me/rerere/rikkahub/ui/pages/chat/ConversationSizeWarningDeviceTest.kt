package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.planCompaction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationSizeWarningDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun at(millis: Long) =
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())

    private fun reply(millis: Long, tokens: Int) =
        UIMessage.assistant("reply")
            .copy(
                createdAt = at(millis),
                usage = TokenUsage(promptTokens = tokens),
            )

    @Test
    fun summaryOnlyUpdateClearsStaleWarningAndFreshUsageIsEvaluated() {
        val original =
            Conversation(
                assistantId = Uuid.random(),
                messageNodes =
                    (1..770).map { MessageNode.of(UIMessage.user("history $it")) } +
                        MessageNode.of(reply(1_000, 330_000)),
            )
        val summary =
            original.planCompaction(0).result("earlier context").copy(createdAtMillis = 2_000)
        var conversation by mutableStateOf(original)
        compose.setContent {
            val size = rememberConversationSizeInfo(conversation)
            Text("warning=${size.showWarning};input=${size.lastAssistantInputTokens}")
        }
        compose.onNodeWithText("warning=true;input=330000").assertIsDisplayed()
        // The nodes are the exact same instance: changing the summary must invalidate remember.
        compose.runOnIdle { conversation = original.copy(contextCompaction = summary) }
        compose.onNodeWithText("warning=false;input=0").assertIsDisplayed()
        compose.runOnIdle {
            conversation =
                conversation.copy(
                    messageNodes = conversation.messageNodes + MessageNode.of(reply(3_000, 60_000))
                )
        }
        compose.onNodeWithText("warning=false;input=60000").assertIsDisplayed()
        compose.runOnIdle {
            conversation =
                conversation.copy(
                    messageNodes = conversation.messageNodes + MessageNode.of(reply(4_000, 330_000))
                )
        }
        compose.onNodeWithText("warning=true;input=330000").assertIsDisplayed()
    }
}
