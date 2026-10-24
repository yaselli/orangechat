package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity

@Dao
interface UsageRecordDAO {
    @Query("SELECT model_id AS modelId, prompt_tokens AS inputTokens FROM usage_record " +
        "WHERE conversation_id = :conversationId AND source IN ('CHAT', 'PROACTIVE') ORDER BY id DESC LIMIT 1")
    fun observeContext(conversationId: String): Flow<ContextUsage?>

    @Insert
    suspend fun insert(record: UsageRecordEntity)

    @Query("SELECT COALESCE(MAX(id), 0) FROM usage_record")
    fun observeLatestId(): Flow<Long>

    /** Keep totals and breakdowns from one consistent database snapshot. */
    @Transaction
    suspend fun getSummary(since: Long, format: String): UsageSummary = UsageSummary(
        totals = getTotals(since),
        buckets = getTotalsByBucket(format, since),
        models = getTotalsByModel(since),
        sources = getTotalsBySource(since),
    )

    @Query(
        "SELECT COUNT(*) AS requestCount, COALESCE(SUM(prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(cached_tokens), 0) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since"
    )
    suspend fun getTotals(since: Long = 0): UsageTotals

    /** 按本地时间分桶汇总，[format] 是 strftime 格式，比如按天用 `%Y-%m-%d`、按月用 `%Y-%m` */
    @Query(
        "SELECT strftime(:format, created_at / 1000, 'unixepoch', 'localtime') AS bucket, " +
            "SUM(prompt_tokens) AS promptTokens, SUM(completion_tokens) AS completionTokens, " +
            "SUM(cached_tokens) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since GROUP BY bucket ORDER BY bucket"
    )
    suspend fun getTotalsByBucket(format: String, since: Long): List<UsageBucket>

    // Preserve provider identity and API name snapshots even when a configured model is deleted.
    @Query(
        "SELECT provider_id AS providerId, model_id AS modelId, MAX(model_name) AS modelName, " +
            "SUM(prompt_tokens) AS promptTokens, SUM(completion_tokens) AS completionTokens, " +
            "SUM(cached_tokens) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since " +
            "GROUP BY provider_id, COALESCE(model_id, model_name) " +
            "ORDER BY SUM(prompt_tokens + completion_tokens) DESC"
    )
    suspend fun getTotalsByModel(since: Long): List<ModelUsage>

    @Query(
        "SELECT source, SUM(prompt_tokens) AS promptTokens, SUM(completion_tokens) AS completionTokens, " +
            "SUM(cached_tokens) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since " +
            "GROUP BY source ORDER BY SUM(prompt_tokens + completion_tokens) DESC"
    )
    suspend fun getTotalsBySource(since: Long): List<SourceUsage>
}

data class UsageTotals(
    val requestCount: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

data class UsageBucket(
    val bucket: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

data class ModelUsage(
    val providerId: String?,
    val modelId: String?,
    val modelName: String?,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

data class SourceUsage(
    val source: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

data class UsageSummary(
    val totals: UsageTotals,
    val buckets: List<UsageBucket>,
    val models: List<ModelUsage>,
    val sources: List<SourceUsage>,
)

data class ContextUsage(val modelId: String?, val inputTokens: Long)

