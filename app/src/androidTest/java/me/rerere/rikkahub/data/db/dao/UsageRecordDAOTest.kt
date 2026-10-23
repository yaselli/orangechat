package me.rerere.rikkahub.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class UsageRecordDAOTest {
    private lateinit var db: AppDatabase
    private val dao get() = db.usageRecordDao()
    private val day = LocalDate.of(2026, 10, 23)
    private val midnight = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun close() { db.close() }

    private fun row(time: Long, source: String, provider: String = "provider", input: Int = 20) = UsageRecordEntity(
        createdAt = time, source = source, providerId = provider, modelId = "model", modelName = "deleted-model",
        assistantId = "owner", conversationId = "chat", promptTokens = input, completionTokens = 5, cachedTokens = 10,
    )

    @Test
    fun rangeAggregationUsesLocalDatesSourcesAndProviderIdentity() = runBlocking {
        assertEquals(UsageTotals(), dao.getTotals())
        assertEquals(0L, dao.observeLatestId().first())
        dao.insert(row(midnight - 1, "CHAT"))
        dao.insert(row(midnight, "CHAT"))
        dao.insert(row(midnight + 1, "TRANSLATION", provider = "another"))
        val summary = dao.getSummary(midnight, "%Y-%m-%d")
        assertEquals(UsageTotals(2, 40, 10, 20), summary.totals)
        assertEquals(listOf(UsageBucket(day.toString(), 40, 10, 20)), summary.buckets)
        assertEquals(2, summary.models.size)
        assertEquals(setOf("provider", "another"), summary.models.map { it.providerId }.toSet())
        assertTrue(summary.models.all { it.modelName == "deleted-model" })
        assertEquals(setOf("CHAT", "TRANSLATION"), summary.sources.map { it.source }.toSet())
        assertEquals(3L, dao.observeLatestId().first())
        assertEquals(UsageTotals(3, 60, 15, 30), dao.getTotals())
    }

    @Test
    fun longSumsDoNotOverflowAndUsageDoesNotDependOnConversationExistence() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at) " +
                "VALUES('chat','owner','title','[]',1,2)",
        )
        dao.insert(row(midnight, "MEMORY", input = Int.MAX_VALUE))
        dao.insert(row(midnight, "MEMORY", input = Int.MAX_VALUE))
        assertEquals(2L * Int.MAX_VALUE, dao.getTotals().promptTokens)
        // Request accounting survives deletion of the actual parent chat.
        db.conversationDao().deleteById("chat")
        assertEquals(2L, dao.getTotals().requestCount)
        val month = dao.getTotalsByBucket("%Y-%m", 0).single()
        assertEquals("2026-10", month.bucket)
        assertEquals(2L * Int.MAX_VALUE, month.promptTokens)
    }
}
