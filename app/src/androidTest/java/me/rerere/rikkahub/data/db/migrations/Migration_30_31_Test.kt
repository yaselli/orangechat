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

    @Test
    fun retainedLegacyVectorTableUpgradesToCurrentVersionWithoutChangingSources() {
        val name = "memory-legacy-vector-30-34-test"
        helper.createDatabase(name, 30).apply {
            execSQL("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at,context_compaction) " +
                "VALUES('chat','title','[]',1,2,'saved-summary')")
            execSQL("INSERT INTO message_node VALUES('node','chat',0,'[{\"id\":\"original\"}]',0)")
            execSQL("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'assistant','original')")
            execSQL("INSERT INTO memory_bank(id,content,type,vector_status,vector_retry_count,created_at,embedding) " +
                "VALUES(8,'legacy memory','fact','done',0,1,'[0.5,0.5]')")
            // Historical v20/v21 schema; retained extra tables are not represented by v30.json.
            execSQL("""CREATE TABLE memory_vector (
                memory_id INTEGER NOT NULL PRIMARY KEY, vector TEXT NOT NULL,
                dimensions INTEGER NOT NULL, model TEXT NOT NULL, created_at INTEGER NOT NULL)""")
            execSQL("INSERT INTO memory_vector VALUES(8,'[0.5,0.5]',2,'old-model',1)")
            execSQL("CREATE INDEX index_memory_vector_memoryId ON memory_vector(memory_id)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 35, true, Migration_30_31, Migration_31_33_HistoryMemory, Migration_33_34, Migration_34_35)
        try {
            db.query("SELECT content FROM MemoryEntity WHERE id=7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("original", cursor.getString(0))
            }
            db.query("SELECT content,embedding FROM memory_bank WHERE id=8").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy memory", cursor.getString(0))
                assertEquals("[0.5,0.5]", cursor.getString(1))
            }
            db.query("SELECT title,context_compaction FROM ConversationEntity WHERE id='chat'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("title", cursor.getString(0))
                assertEquals("saved-summary", cursor.getString(1))
            }
            db.query("SELECT messages,select_index FROM message_node WHERE id='node'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("[{\"id\":\"original\"}]", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            db.query("SELECT COUNT(*) FROM memory_vector").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("INSERT INTO memory_vector VALUES('model','slice','assistant',7,1,'hash','[1,0]')")
            db.execSQL("DELETE FROM MemoryEntity WHERE id=7")
            db.query("SELECT COUNT(*) FROM memory_vector").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.query("PRAGMA foreign_key_check").use { cursor ->
                assertEquals(0, cursor.count)
            }
        } finally { db.close() }
    }

}
