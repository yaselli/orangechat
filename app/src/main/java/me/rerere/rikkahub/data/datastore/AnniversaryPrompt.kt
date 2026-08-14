/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 构建一条最短、可预测的纪念日上下文。
 *
 * 不注入列表、说明或工具定义，避免无谓增加 token。开始当天按“第 1 天”计算。
 */
fun DisplaySetting.buildAnniversaryPrompt(today: LocalDate = LocalDate.now()): String? {
    if (!anniversaryAiInjectionEnabled) return null
    val entry = anniversaries.firstOrNull { it.id == anniversaryAiInjectionId } ?: return null
    val startDate = runCatching { LocalDate.parse(entry.startDate) }.getOrNull() ?: return null
    return if (entry.countdown) {
        val remaining = ChronoUnit.DAYS.between(today, startDate)
        when {
            remaining > 0 -> "[倒数日] 距离用户的“${entry.title}”（${entry.startDate}）还有${remaining}天。"
            remaining == 0L -> "[倒数日] 今天就是用户的“${entry.title}”（${entry.startDate}）。"
            else -> null
        }
    } else {
        val dayNumber = ChronoUnit.DAYS.between(startDate, today) + 1
        if (dayNumber < 1) return null
        "[纪念日] 用户的“${entry.title}”始于${entry.startDate}，今天是第${dayNumber}天。"
    }
}
