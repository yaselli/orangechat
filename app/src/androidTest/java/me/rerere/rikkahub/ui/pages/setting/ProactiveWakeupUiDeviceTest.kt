package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProactiveWakeupUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun optInCanBeEnabledAndAPendingAppointmentCanBeCancelled() {
        val assistant = Assistant(name = "测试角色")
        val task =
            ProactiveWakeup(
                "task",
                assistant.id.toString(),
                "chat",
                System.currentTimeMillis() + 3_600_000,
                "Asia/Shanghai",
                "到点问问今天过得怎么样",
            )
        var setting by
            mutableStateOf(ProactiveMessageSetting(enabled = true, aiWakeups = listOf(task)))
        compose.setContent {
            CompositionLocalProvider(LocalDisplaySettings provides DisplaySetting()) {
                MaterialTheme {
                    ProactiveWakeupSettingsCard(setting, listOf(assistant), true) { change ->
                        setting = change(setting)
                    }
                }
            }
        }
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertTrue(setting.allowAiWakeups) }
        compose.onNodeWithText(task.reason).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals(ProactiveWakeupStatus.Cancelled, setting.aiWakeups.single().status)
        }
        compose.onNodeWithText("用户已取消", substring = true).assertIsDisplayed()
    }
}
