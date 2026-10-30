package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.ChatDisplayMode
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.ThemeMaterialSetting
import me.rerere.rikkahub.data.datastore.UiMaterialStyle
import me.rerere.rikkahub.data.message.MessageQuote
import me.rerere.rikkahub.data.message.messageQuote
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.theme.LocalMaterialBlurEnabled
import me.rerere.rikkahub.ui.theme.LocalThemeMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EverydayChatModeDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyBubbleHasAnAvatarInAllMaterialsAndLongPressOpensItsActions() {
        var role by mutableStateOf(MessageRole.USER)
        var material by mutableStateOf(UiMaterialStyle.FOG_GLASS)
        var mode by mutableStateOf(ChatDisplayMode.EVERYDAY)
        var opened: String? = null
        compose.setContent {
            val display =
                DisplaySetting(
                    chatDisplayMode = mode,
                    showUserAvatar = false,
                    showModelIcon = false,
                )
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDisplaySettings provides display,
                    LocalSettings provides Settings(displaySetting = display),
                    LocalThemeMaterial provides ThemeMaterialSetting(material),
                    LocalMaterialBlurEnabled provides false,
                ) {
                    ChatMessageTextBubbles(
                        text = "第一句\n第二句",
                        role = role,
                        assistant = null,
                        reactions = emptyList(),
                        onReact = null,
                        onUserMessageClick = {},
                        onClickCitation = {},
                        avatar = {
                            Box(Modifier.size(32.dp).testTag("bubble-avatar")) { Text("头像") }
                        },
                        onLongClick = { opened = it },
                    )
                }
            }
        }
        for (style in UiMaterialStyle.entries) {
            for (speaker in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
                compose.runOnIdle {
                    material = style
                    role = speaker
                    opened = null
                }
                compose
                    .onAllNodesWithTag("bubble-avatar", useUnmergedTree = true)
                    .assertCountEquals(2)
                compose.onNodeWithText("第二句").performTouchInput { longClick() }
                compose.runOnIdle { assertEquals("第二句", opened) }
            }
        }
        compose.runOnIdle { mode = ChatDisplayMode.STANDARD }
        compose.onAllNodesWithTag("bubble-avatar", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun everydayBubbleCanBeQuotedCancelledAndSentWithoutLeakingIntoTheNextDraft() {
        val state = ChatInputState()
        val source = UIMessage.assistant("第一句\n第二句")
        var selected by mutableStateOf<String?>(null)
        var sent = emptyList<UIMessagePart>()
        compose.setContent {
            val display = DisplaySetting(chatDisplayMode = ChatDisplayMode.EVERYDAY)
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDisplaySettings provides display,
                    LocalSettings provides Settings(displaySetting = display),
                    LocalMaterialBlurEnabled provides false,
                ) {
                    Column {
                        ChatMessageTextBubbles(
                            text = "第一句\n第二句",
                            role = MessageRole.ASSISTANT,
                            assistant = null,
                            reactions = emptyList(),
                            onReact = null,
                            onUserMessageClick = null,
                            onClickCitation = {},
                            avatar = { Box(Modifier.size(32.dp)) { Text("头像") } },
                            onLongClick = { selected = it },
                        )
                        state.quotedMessage?.let {
                            MessageQuoteCard(it, onCancel = state::clearQuote)
                        }
                        TextField(
                            value = state.textContent.text.toString(),
                            onValueChange = state::setMessageText,
                            modifier = Modifier.testTag("quote-reply"),
                        )
                        Button(
                            onClick = {
                                sent = state.getContents()
                                state.clearInput()
                            }
                        ) {
                            Text("发送引用回复")
                        }
                    }
                    selected?.let { sentence ->
                        ChatMessageActionsSheet(
                            message = source,
                            model = null,
                            onDelete = {},
                            onEdit = {},
                            onShare = {},
                            onFork = {},
                            onSelectAndCopy = {},
                            onWebViewPreview = {},
                            onQuote = {
                                state.quoteMessage(
                                    MessageQuote(source.id.toString(), "沈渡", sentence)
                                )
                            },
                            onDismissRequest = { selected = null },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("第二句").performTouchInput { longClick() }
        compose.onNodeWithText("引用").performClick()
        compose.runOnIdle { assertEquals("第二句", state.quotedMessage?.text) }
        compose.onNodeWithText("取消引用").performClick()
        compose.runOnIdle { assertEquals(null, state.quotedMessage) }
        compose.onNodeWithText("第二句").performTouchInput { longClick() }
        compose.onNodeWithText("引用").performClick()
        compose.onNodeWithTag("quote-reply").performTextInput("我在回第二句")
        compose.onNodeWithText("发送引用回复").performClick()
        compose.runOnIdle {
            assertEquals("第二句", sent.first().messageQuote()?.text)
            assertEquals("我在回第二句", (sent.last() as UIMessagePart.Text).text)
            assertEquals(null, state.quotedMessage)
            state.setMessageText("下一轮")
            assertTrue(state.getContents().none { it.messageQuote() != null })
        }
    }

    @Test
    fun incomingAndOutgoingBubblesMirrorOnlyTheirLowerCorner() {
        val incoming =
            chatBubbleShape(false).createOutline(Size(160f, 80f), LayoutDirection.Ltr, Density(1f))
                as Outline.Rounded
        val outgoing =
            chatBubbleShape(true).createOutline(Size(160f, 80f), LayoutDirection.Ltr, Density(1f))
                as Outline.Rounded
        assertEquals(4f, incoming.roundRect.bottomLeftCornerRadius.x, 0f)
        assertEquals(20f, incoming.roundRect.bottomRightCornerRadius.x, 0f)
        assertEquals(20f, outgoing.roundRect.bottomLeftCornerRadius.x, 0f)
        assertEquals(4f, outgoing.roundRect.bottomRightCornerRadius.x, 0f)
    }
}
