/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.datastore.VisualThemePalette
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.ui.theme.ThemeMaterialBackground
import me.rerere.rikkahub.ui.theme.SoftDarkWallpaperShade

@Composable
fun AssistantBackground(setting: Settings) {
    ThemeMaterialBackground()
    val assistant = setting.getCurrentAssistant()
    val chatBackgroundColor = setting.displaySetting.chatBackgroundColor?.let { it.toComposeColor() }

    when {
        assistant.background != null -> {
            // 用户手动为助手设置的背景图，优先级最高
            val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
            // Preserve the wallpaper's colors. Material tints belong to surfaces,
            // not to an automatic full-screen veil over the user's image.
            AsyncImage(
                model = assistant.background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(backgroundOpacity)
            )
            SoftDarkWallpaperShade()
        }

        chatBackgroundColor != null -> {
            // 用户设置了自定义纯色背景
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(chatBackgroundColor)
            )
        }
        setting.displaySetting.visualThemePalette == VisualThemePalette.APRICOT_CREAM ||
            setting.displaySetting.visualThemePalette == VisualThemePalette.MIST_BLUE -> {
            // Static theme backdrop. User images and explicit colors above always take precedence.
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f),
                            MaterialTheme.colorScheme.background,
                        )
                    )
                )
            )
        }

    }
}
