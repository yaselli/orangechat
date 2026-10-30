package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import me.rerere.rikkahub.data.repository.TranslationHistoryRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class TranslationHistoryDAOTest {
    private lateinit var db: AppDatabase
    private val dao get() = db.translationHistoryDao()
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }
    @After fun close() { db.close() }
    private fun row(id: String, time: Long) = TranslationHistoryEntity(id, "原文 $id", "result $id", "en", "model", time)
    private suspend fun page(): List<TranslationHistoryEntity> {
        val result = dao.pagingAll().load(PagingSource.LoadParams.Refresh(null, 200, false))
        check(result is PagingSource.LoadResult.Page)
        return result.data
    }

    @Test fun savesAndRetainsLatestHundredWithStableTieOrderAndRestorableText() = runBlocking {
        repeat(105) { dao.saveAndTrim(row("%03d".format(it), it.toLong()), 100) }
        val rows = page()
        assertEquals(100, rows.size)
        assertEquals("104", rows.first().id)
        assertEquals("005", rows.last().id)
        assertEquals("原文 104", rows.first().sourceText)
        assertEquals("result 104", rows.first().translatedText)
        assertEquals("en", rows.first().targetLanguage)
        dao.deleteAll()
        dao.saveAndTrim(row("a", 1), 100)
        dao.saveAndTrim(row("b", 1), 100)
        assertEquals(listOf("b", "a"), page().map { it.id })
        dao.deleteById("b")
        assertEquals(listOf("a"), page().map { it.id })
        dao.deleteAll()
        assertTrue(page().isEmpty())
    }

    @Test fun failedTrimRollsBackInsertionAndDoesNotEraseExistingHistory() = runBlocking {
        dao.insert(row("old", 1))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_trim BEFORE DELETE ON translation_history " +
            "BEGIN SELECT RAISE(ABORT, 'test trim failure'); END")
        assertNotNull(runCatching { dao.saveAndTrim(row("new", 2), 1) }.exceptionOrNull())
        assertEquals(listOf("old"), page().map { it.id })
    }

    @Test fun blankSourcesOrResultsCannotCreateHistoryRows() = runBlocking {
        val repository = TranslationHistoryRepository(dao)
        repository.add("input", "", "en", Uuid.random())
        repository.add(" ", "result", "en", Uuid.random())
        assertTrue(page().isEmpty())
        repository.add("input", "result", "en", Uuid.random())
        assertEquals(1, page().size)
    }
}
