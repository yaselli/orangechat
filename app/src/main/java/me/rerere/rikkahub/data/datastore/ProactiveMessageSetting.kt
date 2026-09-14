/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
data class ProactiveMessageSetting(
    val enabled: Boolean = false,
    val minIntervalMinutes: Int = 30,
    val maxIntervalMinutes: Int = 90,
    // 同一条真实用户消息之后，最多允许几次主动追问；用户一回复即清零。
    val maxFollowUpMessages: Int = 2,
    val assistantId: String = "",
    // 是否允许 AI 根据上下文判断后强制跳转屏幕到聊天界面
    val allowForceJump: Boolean = false,
    // 主动消息是否可以按需调用应用使用情况工具；正常聊天不受影响。
    val allowProactiveAppUsage: Boolean = false,
    // Legacy serialization fields only; proactive injection now uses the shared extra-info settings.
    val proactiveScreenOcrEnabled: Boolean = false,
    val proactiveScreenOcrDelayMinutes: Int = 45,
    val jumpIdleThresholdMinutes: Int = 120, // 用户多久没回复(分钟)才允许跳转屏幕，默认2小时
)
