/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingExtraInjectionPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("额外注入") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("时间信息") },
                ) {
                    item(
                        headlineContent = { Text("当前时间注入") },
                        supportingContent = {
                            Text("每次对话向 AI 提供准确的当前时间；关闭后不会让 AI 根据历史时间自行推测。")
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.systemToolsSetting.timeContextInjectionEnabled,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            systemToolsSetting = settings.systemToolsSetting.copy(
                                                timeContextInjectionEnabled = enabled,
                                            ),
                                        ),
                                    )
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("回复间隔提醒") },
                        supportingContent = {
                            Text("在间隔较大的消息前自动加入经过时长，帮助 AI 理解对话节奏；不包含当前时间。")
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.systemToolsSetting.replyIntervalReminderEnabled,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            systemToolsSetting = settings.systemToolsSetting.copy(
                                                replyIntervalReminderEnabled = enabled,
                                            ),
                                        ),
                                    )
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}
