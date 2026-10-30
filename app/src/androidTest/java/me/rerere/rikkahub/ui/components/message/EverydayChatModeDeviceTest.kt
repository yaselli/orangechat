package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.ChatDisplayMode
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.ThemeMaterialSetting
import me.rerere.rikkahub.data.datastore.UiMaterialStyle
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.LocalSettings
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
        var opened = false
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
                        onLongClick = { opened = true },
                    )
                }
            }
        }
        for (style in UiMaterialStyle.entries) {
            for (speaker in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
                compose.runOnIdle {
                    material = style
                    role = speaker
                    opened = false
                }
                compose
                    .onAllNodesWithTag("bubble-avatar", useUnmergedTree = true)
                    .assertCountEquals(2)
                compose.onNodeWithText("第二句").performTouchInput { longClick() }
                compose.runOnIdle { assertTrue("Long press did not open message actions", opened) }
            }
        }
        compose.runOnIdle { mode = ChatDisplayMode.STANDARD }
        compose.onAllNodesWithTag("bubble-avatar", useUnmergedTree = true).assertCountEquals(0)
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
