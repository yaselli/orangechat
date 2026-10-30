package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card as NativeCard
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.DropdownMenu as NativeDropdownMenu
import androidx.compose.material3.ListItem as NativeListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet as NativeModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface as NativeSurface
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import me.rerere.rikkahub.data.datastore.UiMaterialStyle

/**
 * Common material treatment only; native components retain layout, semantics, actions and colors.
 */
@Composable
private fun materialEnabled(color: Color): Boolean {
    if (LocalThemeMaterial.current.style == UiMaterialStyle.ORIGINAL || color.alpha <= 0f)
        return false
    val scheme = MaterialTheme.colorScheme
    // Brand swatches, error/status colors and other meaningful fills retain their exact colors.
    val opaque = color.copy(alpha = 1f)
    // ColorScheme updates observable fields in place. Compare current values rather
    // than caching by its stable object, so live palette/mode changes keep working.
    return opaque == scheme.background.copy(alpha = 1f) ||
        opaque == scheme.surface.copy(alpha = 1f) ||
        opaque == scheme.surfaceDim.copy(alpha = 1f) ||
        opaque == scheme.surfaceBright.copy(alpha = 1f) ||
        opaque == scheme.surfaceContainerLowest.copy(alpha = 1f) ||
        opaque == scheme.surfaceContainerLow.copy(alpha = 1f) ||
        opaque == scheme.surfaceContainer.copy(alpha = 1f) ||
        opaque == scheme.surfaceContainerHigh.copy(alpha = 1f) ||
        opaque == scheme.surfaceContainerHighest.copy(alpha = 1f) ||
        opaque == scheme.surfaceVariant.copy(alpha = 1f)
}

@Composable
private fun CardColors.forPlainSettings(): CardColors {
    if (
        LocalThemeMaterial.current.style != UiMaterialStyle.ORIGINAL ||
            !LocalSettingsWallpaperActive.current
    )
        return this
    val opacity = LocalMaterialSurfaceOpacity.current
    return copy(
        containerColor = containerColor.copy(alpha = minOf(containerColor.alpha, opacity)),
        disabledContainerColor =
            disabledContainerColor.copy(alpha = minOf(disabledContainerColor.alpha, opacity)),
    )
}

@Composable
internal fun MaterialCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tint = colors.containerColor
    val active = materialEnabled(tint)
    NativeCard(
        modifier = if (active) modifier.themeMaterial(shape, tint) else modifier,
        shape = shape,
        colors =
            if (active)
                colors.copy(
                    containerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                )
            else colors.forPlainSettings(),
        elevation = elevation,
        border = border,
        content = content,
    )
}

@Composable
internal fun MaterialCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tint = if (enabled) colors.containerColor else colors.disabledContainerColor
    val active = materialEnabled(tint)
    NativeCard(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = if (active) modifier.themeMaterial(shape, tint) else modifier,
        shape = shape,
        colors =
            if (active)
                colors.copy(
                    containerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                )
            else colors.forPlainSettings(),
        elevation = elevation,
        border = border,
        content = content,
    )
}

@Composable
internal fun MaterialElevatedCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.elevatedShape,
    colors: CardColors = CardDefaults.elevatedCardColors(),
    elevation: CardElevation = CardDefaults.elevatedCardElevation(),
    content: @Composable ColumnScope.() -> Unit,
) {
    MaterialCard(
        modifier = modifier,
        shape = shape,
        colors = colors,
        elevation = elevation,
        content = content,
    )
}

@Composable
internal fun MaterialElevatedCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.elevatedShape,
    colors: CardColors = CardDefaults.elevatedCardColors(),
    elevation: CardElevation = CardDefaults.elevatedCardElevation(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    MaterialCard(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier,
        shape = shape,
        colors = colors,
        elevation = elevation,
        content = content,
    )
}

@Composable
internal fun MaterialOutlinedCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.outlinedShape,
    colors: CardColors = CardDefaults.outlinedCardColors(),
    elevation: CardElevation = CardDefaults.outlinedCardElevation(),
    border: BorderStroke = CardDefaults.outlinedCardBorder(),
    content: @Composable ColumnScope.() -> Unit,
) {
    MaterialCard(
        modifier = modifier,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        content = content,
    )
}

@Composable
internal fun MaterialOutlinedCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.outlinedShape,
    colors: CardColors = CardDefaults.outlinedCardColors(),
    elevation: CardElevation = CardDefaults.outlinedCardElevation(),
    border: BorderStroke = CardDefaults.outlinedCardBorder(enabled),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    MaterialCard(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        content = content,
    )
}

@Composable
internal fun MaterialSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    // Full-page rectangular surfaces are backgrounds, not frosted overlays.
    val active = shape != RectangleShape && materialEnabled(color)
    val pageBackground =
        LocalSettingsWallpaperActive.current &&
            shape == RectangleShape &&
            color == MaterialTheme.colorScheme.surface
    NativeSurface(
        modifier = if (active) modifier.themeMaterial(shape, color) else modifier,
        shape = shape,
        color = if (active || pageBackground) Color.Transparent else color,
        contentColor = contentColor,
        tonalElevation = if (active) 0.dp else tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
        content = content,
    )
}

@Composable
internal fun MaterialSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    // Full-page rectangular surfaces are backgrounds, not frosted overlays.
    val active = shape != RectangleShape && materialEnabled(color)
    val pageBackground =
        LocalSettingsWallpaperActive.current &&
            shape == RectangleShape &&
            color == MaterialTheme.colorScheme.surface
    NativeSurface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = if (active) modifier.themeMaterial(shape, color) else modifier,
        shape = shape,
        color = if (active || pageBackground) Color.Transparent else color,
        contentColor = contentColor,
        tonalElevation = if (active) 0.dp else tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
        content = content,
    )
}

@Composable
internal fun MaterialSurface(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    // Full-page rectangular surfaces are backgrounds, not frosted overlays.
    val active = shape != RectangleShape && materialEnabled(color)
    val pageBackground =
        LocalSettingsWallpaperActive.current &&
            shape == RectangleShape &&
            color == MaterialTheme.colorScheme.surface
    NativeSurface(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = if (active) modifier.themeMaterial(shape, color) else modifier,
        shape = shape,
        color = if (active || pageBackground) Color.Transparent else color,
        contentColor = contentColor,
        tonalElevation = if (active) 0.dp else tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
        content = content,
    )
}

@Composable
internal fun MaterialSurface(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    // Full-page rectangular surfaces are backgrounds, not frosted overlays.
    val active = shape != RectangleShape && materialEnabled(color)
    val pageBackground =
        LocalSettingsWallpaperActive.current &&
            shape == RectangleShape &&
            color == MaterialTheme.colorScheme.surface
    NativeSurface(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = if (active) modifier.themeMaterial(shape, color) else modifier,
        shape = shape,
        color = if (active || pageBackground) Color.Transparent else color,
        contentColor = contentColor,
        tonalElevation = if (active) 0.dp else tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
        content = content,
    )
}

@Composable
internal fun MaterialListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
    tonalElevation: Dp = ListItemDefaults.Elevation,
    shadowElevation: Dp = ListItemDefaults.Elevation,
) {
    val active = materialEnabled(colors.containerColor)
    NativeListItem(
        headlineContent = headlineContent,
        modifier =
            if (active) modifier.themeMaterial(RectangleShape, colors.containerColor) else modifier,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = if (active) colors.copy(containerColor = Color.Transparent) else colors,
        tonalElevation = if (active) 0.dp else tonalElevation,
        shadowElevation = shadowElevation,
    )
}

@Composable
internal fun MaterialDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val active = materialEnabled(containerColor)
    CompositionLocalProvider(
        LocalMaterialBackdrop provides null,
        LocalMaterialBlurEnabled provides false,
    ) {
        NativeDropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier =
                if (active)
                    modifier.themeMaterial(shape, containerColor, role = MaterialRole.DIALOG)
                else modifier,
            offset = offset,
            scrollState = scrollState,
            properties = properties,
            shape = shape,
            containerColor = if (active) Color.Transparent else containerColor,
            tonalElevation = if (active) 0.dp else tonalElevation,
            shadowElevation = shadowElevation,
            border = border,
            content = content,
        )
    }
}

@Composable
internal fun MaterialModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val active = materialEnabled(containerColor)
    CompositionLocalProvider(
        LocalMaterialBackdrop provides null,
        LocalMaterialBlurEnabled provides false,
    ) {
        NativeModalBottomSheet(
            onDismissRequest = onDismissRequest,
            // The native sheet applies its offset inside this modifier. Drawing a background
            // here paints at the unshifted bounds (over the chat and scrim). Let its own
            // Surface draw the readable container after positioning and animate it as one layer.
            modifier = modifier,
            sheetState = sheetState,
            sheetMaxWidth = sheetMaxWidth,
            sheetGesturesEnabled = sheetGesturesEnabled,
            shape = shape,
            containerColor = if (active) containerColor.copy(alpha = 1f) else containerColor,
            contentColor = contentColor,
            tonalElevation = if (active) 0.dp else tonalElevation,
            scrimColor = scrimColor,
            dragHandle = dragHandle,
            contentWindowInsets = contentWindowInsets,
            properties = properties,
            content = content,
        )
    }
}
