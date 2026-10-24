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
class Migration_29_30_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationPreservesMemoryIdsScopesAndHistoryAndMatchesRoomSchema() {
        val name = "memory-29-30-test"
        val messages = "[{\"id\":\"selected\"},{\"id\":\"alternate\"}]"
        helper.createDatabase(name, 29).apply {
            execSQL("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) " +
                "VALUES('conversation','title','[]',1,2)")
            execSQL("INSERT INTO message_node VALUES('node','conversation',0,?,1)", arrayOf(messages))
            execSQL("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'assistant','original')")
            execSQL("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(19,'__global__','global')")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 30, true, Migration_29_30)
        try {
            db.query("SELECT id,assistant_id,content,source_kind,revision,created_at,source_message_id " +
                "FROM MemoryEntity ORDER BY id").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7, cursor.getInt(0))
                assertEquals("assistant", cursor.getString(1))
                assertEquals("original", cursor.getString(2))
                assertEquals("legacy", cursor.getString(3))
                assertEquals(1L, cursor.getLong(4))
                assertEquals(0L, cursor.getLong(5))
                assertEquals("", cursor.getString(6))
                assertTrue(cursor.moveToNext())
                assertEquals(19, cursor.getInt(0))
                assertEquals("__global__", cursor.getString(1))
            }
            db.query("SELECT messages,select_index FROM message_node").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(messages, cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
            db.query("SELECT context_compaction FROM ConversationEntity").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
            }
        } finally { db.close() }
    }
}
