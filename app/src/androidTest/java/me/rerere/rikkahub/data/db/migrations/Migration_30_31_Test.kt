package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_30_31_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationKeepsSourcesAndValidatesDerivedIndexSchema() {
        val name = "memory-30-31-test"
        helper.createDatabase(name, 30).apply {
            execSQL("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) " +
                "VALUES('chat','title','[]',1,2)")
            execSQL("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'assistant','original')")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 31, true, Migration_30_31)
        try {
            db.query("SELECT id,assistant_id,content FROM MemoryEntity").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7, cursor.getInt(0))
                assertEquals("assistant", cursor.getString(1))
                assertEquals("original", cursor.getString(2))
            }
            db.execSQL("INSERT INTO memory_vector VALUES('model','slice','assistant',7,1,'hash','[1,0]')")
            db.execSQL("INSERT INTO memory_recall_snapshot VALUES('key','chat','assistant','user','hash','[]',1)")
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("DELETE FROM MemoryEntity WHERE id=7")
            db.query("SELECT COUNT(*) FROM memory_vector").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.execSQL("DELETE FROM ConversationEntity WHERE id='chat'")
            db.query("SELECT COUNT(*) FROM memory_recall_snapshot").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally { db.close() }
    }
}
