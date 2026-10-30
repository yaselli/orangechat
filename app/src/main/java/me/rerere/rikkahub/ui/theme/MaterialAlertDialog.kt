package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import me.rerere.rikkahub.data.datastore.UiMaterialStyle

/** Shared modal treatment. Never attach a popup to a background capture from another window. */
@Composable
internal fun MaterialAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val active = LocalThemeMaterial.current.style != UiMaterialStyle.ORIGINAL
    CompositionLocalProvider(
        LocalMaterialBackdrop provides null,
        LocalMaterialBlurEnabled provides false,
    ) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier =
                modifier.themeMaterial(
                    shape,
                    containerColor,
                    role = MaterialRole.DIALOG,
                    hazeState = null,
                ),
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = shape,
            containerColor = if (active) Color.Transparent else containerColor,
            iconContentColor = iconContentColor,
            titleContentColor = titleContentColor,
            textContentColor = textContentColor,
            tonalElevation = if (active) 0.dp else tonalElevation,
            properties = properties,
        )
    }
}

/** Content-only dialogs retain their custom layout and receive the same safe popup backdrop. */
@Composable
internal fun MaterialAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMaterialBackdrop provides null, LocalMaterialBlurEnabled provides false) {
        BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier, properties = properties) {
            Box(Modifier.themeMaterial(
                shape = AlertDialogDefaults.shape,
                tint = AlertDialogDefaults.containerColor,
                role = MaterialRole.DIALOG,
                hazeState = null,
            )) { content() }
        }
    }
}
