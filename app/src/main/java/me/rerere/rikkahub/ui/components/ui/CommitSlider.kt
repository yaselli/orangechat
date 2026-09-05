package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Keep drag updates local; persist only the completed gesture. */
@Composable
fun CommitSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    // A new persisted value (including resets) replaces the local draft.
    var draft by remember(value) { mutableFloatStateOf(value) }
    Slider(
        value = draft,
        onValueChange = { draft = it },
        onValueChangeFinished = {
            if (draft != value) onValueChange(draft)
        },
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
    )
}
