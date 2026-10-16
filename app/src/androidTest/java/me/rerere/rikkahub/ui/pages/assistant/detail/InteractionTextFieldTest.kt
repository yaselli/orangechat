package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InteractionTextFieldTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun delayedSaveEchoDoesNotResetChineseInputOrCursor() {
        val saved = mutableStateOf("")
        val edits = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                InteractionTextField(saved.value, "后缀", 80) { edits += it }
            }
        }
        val field = compose.onNodeWithText("后缀")
        field.performClick().performTextInput("的")
        field.performTextInput("肩膀")
        // An older disk write finishes after the editor already contains the complete phrase.
        compose.runOnIdle { saved.value = "的" }
        field.performTextInput("，并抱了抱你")
        compose.runOnIdle {
            assertEquals("的肩膀，并抱了抱你", edits.last())
        }
        assertEquals(TextRange(9), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        // Editing the middle must retain the explicitly chosen selection too.
        field.performTextInputSelection(TextRange(3))
        compose.runOnIdle { saved.value = "的肩膀" }
        field.performTextInput("呀")
        compose.runOnIdle { assertEquals("的肩膀呀，并抱了抱你", edits.last()) }
        assertEquals(TextRange(4), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }
}
