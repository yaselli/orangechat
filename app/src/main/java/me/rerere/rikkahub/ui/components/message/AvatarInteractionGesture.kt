package me.rerere.rikkahub.ui.components.message

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Transform only the avatar drawing; shaking must never remeasure the surrounding chat layout. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun avatarInteractionModifier(pulse: String?, onDoubleClick: (() -> Unit)?): Modifier {
    val initialPulse = remember { pulse }
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(pulse) {
        if (pulse != null && pulse != initialPulse) {
            try {
                for (angle in listOf(-9f, 9f, -6f, 6f, 0f)) rotation.animateTo(angle, tween(45))
            } finally {
                withContext(NonCancellable) { rotation.snapTo(0f) }
            }
        }
    }
    val drawing = Modifier.graphicsLayer { rotationZ = rotation.value }
    return if (onDoubleClick == null) drawing else drawing.combinedClickable(
        onClick = {}, onDoubleClick = onDoubleClick, onClickLabel = "双击头像互动",
    )
}
