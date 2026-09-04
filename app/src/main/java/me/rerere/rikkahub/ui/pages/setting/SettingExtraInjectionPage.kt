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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.SystemToolsSetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingExtraInjectionPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val option = settings.systemToolsSetting
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val update: (SystemToolsSetting) -> Unit = { next ->
        vm.updateSettings(settings.copy(systemToolsSetting = next))
    }

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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("总开关") },
                ) {
                    injectionToggle(
                        title = "启用额外信息注入",
                        description = "发送普通聊天消息前，按下面的开关收集一次背景信息。",
                        checked = option.extraInfoInjectionEnabled,
                        onCheckedChange = { update(option.copy(extraInfoInjectionEnabled = it)) },
                    )
                    injectionToggle(
                        title = "保存注入内容",
                        description = "开启后作为隐藏上下文保存到聊天历史；" +
                            "关闭时只供本次模型请求使用。",
                        checked = option.persistExtraInfoInjection,
                        enabled = option.extraInfoInjectionEnabled,
                        onCheckedChange = { update(option.copy(persistExtraInfoInjection = it)) },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("基础信息") },
                ) {
                    injectionToggle(
                        title = "当前时间",
                        description = "准确的本地日期、星期、时间和时区。",
                        checked = option.timeContextInjectionEnabled,
                        enabled = option.extraInfoInjectionEnabled,
                        onCheckedChange = { update(option.copy(timeContextInjectionEnabled = it)) },
                    )
                    injectionToggle(
                        title = "电池信息",
                        description = "电量、充电状态和电池温度。",
                        checked = option.batteryContextInjectionEnabled,
                        enabled = option.extraInfoInjectionEnabled,
                        onCheckedChange = { update(option.copy(batteryContextInjectionEnabled = it)) },
                    )
                    injectionToggle(
                        title = "当前天气",
                        description = "根据设备坐标读取天气、温度、湿度和风速，" +
                            "需要位置与网络权限。",
                        checked = option.weatherContextInjectionEnabled,
                        enabled = option.extraInfoInjectionEnabled,
                        onCheckedChange = { update(option.copy(weatherContextInjectionEnabled = it)) },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("位置") },
                ) {
                    injectionToggle(
                        title = "当前位置",
                        description = "读取设备坐标；只有在本项开启时才会定位。",
                        checked = option.locationContextInjectionEnabled,
                        enabled = option.extraInfoInjectionEnabled,
                        onCheckedChange = { update(option.copy(locationContextInjectionEnabled = it)) },
                    )
                    injectionToggle(
                        title = "精确地址",
                        description = "把坐标转换为详细地址，" +
                            "需要先在系统工具中配置高德 API Key。",
                        checked = option.preciseLocationContextInjectionEnabled,
                        enabled = option.extraInfoInjectionEnabled && option.locationContextInjectionEnabled,
                        onCheckedChange = {
                            update(option.copy(preciseLocationContextInjectionEnabled = it))
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("屏幕与应用") },
                ) {
                    injectionToggle(
                        title = "当前屏幕应用",
                        description = "读取当前窗口所属应用，需要开启橘瓣无障碍服务。",
                        checked = option.currentScreenAppContextInjectionEnabled,
                        enabled = option.extraInfoInjectionEnabled,
                        onCheckedChange = {
                            update(option.copy(currentScreenAppContextInjectionEnabled = it))
                        },
                    )
                    injectionToggle(
                        title = "最近应用使用情况",
                        description = "读取今天使用时间最长的 3 个应用，" +
                            "需要使用情况访问权限。",
                        checked = option.recentAppUsageContextInjectionEnabled,
                        enabled = option.extraInfoInjectionEnabled,
                        onCheckedChange = {
                            update(option.copy(recentAppUsageContextInjectionEnabled = it))
                        },
                    )
                    injectionToggle(
                        title = "当前屏幕文字（OCR）",
                        description = "截取当前屏幕后识别可见文字，最多 4000 字，" +
                            "需要开启无障碍服务。",
                        checked = option.screenTextContextInjectionEnabled,
                        enabled = option.extraInfoInjectionEnabled,
                        onCheckedChange = { update(option.copy(screenTextContextInjectionEnabled = it)) },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("通知与记忆") },
                ) {
                    injectionToggle(
                        title = "最近通知",
                        description = "读取最近 24 小时内最多 5 条通知，需要通知访问权限。",
                        checked = option.notificationsContextInjectionEnabled,
                        enabled = option.extraInfoInjectionEnabled,
                        onCheckedChange = {
                            update(option.copy(notificationsContextInjectionEnabled = it))
                        },
                    )
                    injectionToggle(
                        title = "相关记忆",
                        description = "按照本次消息检索内置记忆库。",
                        checked = option.memoryContextInjectionEnabled,
                        enabled = option.extraInfoInjectionEnabled,
                        onCheckedChange = { update(option.copy(memoryContextInjectionEnabled = it)) },
                    )
                    injectionToggle(
                        title = "允许重复检索记忆",
                        description = "工具调用继续生成时再次刷新注入内容；" +
                            "关闭时同一条消息只收集一次。",
                        checked = option.allowRepeatedMemoryContextSearch,
                        enabled = option.extraInfoInjectionEnabled && option.memoryContextInjectionEnabled,
                        onCheckedChange = {
                            update(option.copy(allowRepeatedMemoryContextSearch = it))
                        },
                    )
                    item(
                        headlineContent = { Text("记忆数量：${option.memoryContextInjectionLimit}") },
                        supportingContent = {
                            Slider(
                                value = option.memoryContextInjectionLimit.toFloat(),
                                onValueChange = {
                                    update(option.copy(memoryContextInjectionLimit = it.roundToInt()))
                                },
                                valueRange = 1f..20f,
                                steps = 18,
                                enabled = option.extraInfoInjectionEnabled &&
                                    option.memoryContextInjectionEnabled,
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("等待时间") },
                ) {
                    item(
                        headlineContent = {
                            Text("注入超时：${option.extraInfoInjectionTimeoutSeconds} 秒")
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    "所有项目并行读取；超时或失败的项目会跳过，" +
                                        "不会编造替代内容。",
                                )
                                Slider(
                                    value = option.extraInfoInjectionTimeoutSeconds.toFloat(),
                                    onValueChange = {
                                        update(
                                            option.copy(
                                                extraInfoInjectionTimeoutSeconds = it.roundToInt(),
                                            ),
                                        )
                                    },
                                    valueRange = 1f..120f,
                                    steps = 118,
                                    enabled = option.extraInfoInjectionEnabled,
                                )
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("权限说明") },
                ) {
                    item(
                        headlineContent = { Text("敏感信息默认关闭") },
                        supportingContent = {
                            Text(
                                "位置、通知、应用使用情况和屏幕内容只有对应开关与" +
                                    "系统权限都开启时才会读取。缺少权限时该项直接跳过；" +
                                    "可前往“设置 → 系统工具”授权。",
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("主动消息不会自动查岗") },
                        supportingContent = {
                            Text("这里的敏感注入只作用于你主动发送的普通聊天消息。")
                        },
                    )
                }
            }
        }
    }
}

private fun CardGroupScope.injectionToggle(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    item(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}
