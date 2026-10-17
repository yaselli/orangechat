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
class Migration_31_33_HistoryMemory_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationPreservesSourcesAndCascadesDerivedVectors() {
        val name = "history-memory-31-34"
        helper.createDatabase(name, 31).apply {
            execSQL("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) " +
                "VALUES('chat','title','[]',1,2)")
            execSQL("INSERT INTO message_node VALUES('node','chat',0,'original history',0)")
            execSQL("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'assistant','original memory')")
            execSQL("INSERT INTO memory_vector VALUES('model','slice','assistant',7,1,'hash','[1,0]')")
            execSQL("INSERT INTO memory_recall_snapshot VALUES('key','chat','assistant','user','hash','[]',1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 34, true, Migration_31_33_HistoryMemory, Migration_33_34)
        try {
            db.query("SELECT messages FROM message_node").use {
                assertTrue(it.moveToFirst())
                assertEquals("original history", it.getString(0))
            }
            db.query("SELECT COUNT(*) FROM memory_vector").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            db.execSQL("INSERT INTO history_memory_vector " +
                "VALUES('model','history','assistant','chat','node','message','hash',0,8,'[1,0]')")
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("DELETE FROM message_node WHERE id='node'")
            db.query("SELECT COUNT(*) FROM history_memory_vector").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        } finally { db.close() }
    }
}
