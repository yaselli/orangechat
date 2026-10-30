/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Github
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ProjectCommunityDialog
import me.rerere.rikkahub.ui.components.ui.icons.TencentQQIcon
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.ProjectLinks
import me.rerere.rikkahub.utils.openUrl

/** Keep the existing route while replacing inherited donation services with this fork's support links. */
@Composable
fun SettingDonatePage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    var showCommunity by remember { mutableStateOf(false) }
    if (showCommunity) ProjectCommunityDialog(onDismiss = { showCommunity = false })

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.donate_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { paddings ->
        Column(
            modifier = Modifier.padding(paddings).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CardGroup {
                item(
                    headlineContent = { Text("西瓜瓣项目主页") },
                    supportingContent = { Text("查看代码、版本和更新说明，也可以给项目点一颗 Star。") },
                    leadingContent = { Icon(HugeIcons.Github, null) },
                    onClick = { context.openUrl(ProjectLinks.HOME) },
                )
                item(
                    headlineContent = { Text("反馈问题与建议") },
                    supportingContent = { Text("提交问题时附上版本号和复现步骤，日志中请隐藏密钥与私人聊天。") },
                    onClick = { context.openUrl(ProjectLinks.ISSUES) },
                )
                item(
                    headlineContent = { Text("西瓜瓣交流群") },
                    supportingContent = { Text("QQ群：${ProjectLinks.QQ_GROUP}") },
                    leadingContent = { Icon(TencentQQIcon, null) },
                    onClick = { showCommunity = true },
                )
            }
        }
    }
}
