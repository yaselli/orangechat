package me.rerere.ai.provider

import kotlin.time.Instant

/**
 * 提供商的额度状态，由 [Provider.getQuota] 返回。
 *
 * 按语义而不是按提供商分类型，界面按类型分别展示；新的提供商优先复用已有类型。
 */
sealed interface ProviderQuota {
    /** 余额。用户自定义查询（[QuotaQuery]）的结果都归到这里 */
    data class Balance(val value: String) : ProviderQuota

    /** 订阅制的用量限制，按时间窗口统计已用比例 */
    data class UsageLimits(
        val windows: List<UsageWindow>,
        // 已经触发限额，暂时不能继续使用
        val limitReached: Boolean,
    ) : ProviderQuota
}

data class UsageWindow(
    // 0~100
    val usedPercent: Double,
    // 窗口长度，未知时为 null
    val windowMinutes: Int?,
    val resetsAt: Instant?,
)
