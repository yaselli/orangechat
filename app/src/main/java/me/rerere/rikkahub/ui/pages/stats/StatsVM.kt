/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.dao.getMessageCountPerDay
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
    private val usageRecordDAO: UsageRecordDAO,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    private val range = MutableStateFlow(UsageRange.MONTH)
    private val refreshCount = MutableStateFlow(0)
    // Stop observing and aggregating the ledger when the statistics screen has no subscribers.
    @OptIn(ExperimentalCoroutinesApi::class)
    val usage = combine(range, usageRecordDAO.observeLatestId(), refreshCount) { selected, _, _ -> selected }
        .transformLatest { selected ->
            emit(RequestUsageStats(range = selected))
            try {
                val today = LocalDate.now()
                val summary = withContext(Dispatchers.IO) {
                    usageRecordDAO.getSummary(
                        usageSince(selected, today, ZoneId.systemDefault()),
                        if (selected == UsageRange.ALL) "%Y-%m" else "%Y-%m-%d",
                    )
                }
                val settings = settingsStore.settingsFlow.value
                val models = settings.providers.flatMap { it.models }.associateBy { it.id.toString() }
                emit(RequestUsageStats(
                    range = selected,
                    isLoading = false,
                    totals = summary.totals,
                    points = usagePoints(selected, summary.buckets, today),
                    models = summary.models.map { item ->
                        val model = models[item.modelId]
                        val name = model?.displayName?.takeIf { it.isNotBlank() }
                            ?: item.modelName?.takeIf { it.isNotBlank() } ?: "未知模型"
                        val providerName = settings.providers.firstOrNull {
                            it.id.toString() == item.providerId
                        }?.name ?: item.providerId?.take(8)
                        UsageRank(listOfNotNull(name, providerName).joinToString(" · "),
                            item.promptTokens, item.completionTokens, item.cachedTokens)
                    },
                    sources = summary.sources.map {
                        UsageRank(it.source, it.promptTokens, it.completionTokens, it.cachedTokens)
                    },
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emit(RequestUsageStats(range = selected, isLoading = false, hasError = true))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RequestUsageStats())

    init {
        viewModelScope.launch { loadStats() }
    }

    fun selectRange(selected: UsageRange) { range.value = selected }

    fun refreshUsage() { refreshCount.value += 1 }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()

        // 热力图起始日期（52 周前的周日），格式 "yyyy-MM-dd" 直接与 JSON 中的 LocalDateTime 前缀比较
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
            .toString()

        // 基于用户消息的 createdAt 统计每日活跃消息数，SQLite 侧 GROUP BY，返回 ≤371 行
        val conversationsPerDay = withContext(Dispatchers.IO) {
            messageNodeDAO
                .getMessageCountPerDay(startDate)
                .mapNotNull { entry ->
                    runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                }
                .toMap()
        }

        val totalConversations = conversationDAO.countAll()

        // json_each() + json_extract() 在 SQLite 侧聚合，不再加载完整 JSON 到 Kotlin
        val tokenStats = messageNodeDAO.getTokenStats()

        val launchCount = settingsStore.settingsFlow.value.launchCount

        _stats.value = AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = tokenStats.totalMessages,
            totalPromptTokens = tokenStats.promptTokens,
            totalCompletionTokens = tokenStats.completionTokens,
            totalCachedTokens = tokenStats.cachedTokens,
            conversationsPerDay = conversationsPerDay,
            launchCount = launchCount,
        )
    }
}
