package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.room.util.TableInfo
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
class MemoryOrganizationMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun additiveMigrationPreservesDataAndCascadesOnlyClaimsAndEvidence() {
        val name = "memory-organization-31-34"
        helper.createDatabase(name, 31).apply {
            execSQL("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) VALUES('chat','','[]',1,2)")
            execSQL("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'assistant','original memory')")
            execSQL("INSERT INTO message_node VALUES('node','chat',0,'original history',0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 37, true, Migration_31_33_HistoryMemory, Migration_33_34, Migration_34_35, Migration_35_36, Migration_36_37)
        try {
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE name='memory_graph'").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            // Room's schema validator compares referenced table names case-sensitively.
            assertEquals(
                setOf(TableInfo.ForeignKey("ConversationEntity", "CASCADE", "NO ACTION", listOf("conversationId"), listOf("id"))),
                TableInfo.read(db, "memory_organization_batch").foreignKeys,
            )
            assertEquals(
                setOf(TableInfo.ForeignKey("MemoryEntity", "CASCADE", "NO ACTION", listOf("memoryId"), listOf("id"))),
                TableInfo.read(db, "memory_evidence").foreignKeys,
            )
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("INSERT INTO memory_organization_batch " +
                "VALUES('batch','assistant','assistant','chat','[\"message\"]','failed','safe status',1)")
            db.execSQL("INSERT OR IGNORE INTO memory_organization_batch " +
                "VALUES('batch','assistant','assistant','chat','[\"message\"]','running','',2)")
            db.query("SELECT state FROM memory_organization_batch").use {
                assertTrue(it.moveToFirst())
                assertEquals("failed", it.getString(0))
            }
            db.execSQL("INSERT INTO memory_evidence " +
                "VALUES(7,'assistant','chat','node','message','hash','user','date','actual quote')")
            db.execSQL("DELETE FROM MemoryEntity WHERE id=7")
            db.query("SELECT COUNT(*) FROM memory_evidence").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            db.query("SELECT messages FROM message_node WHERE id='node'").use {
                assertTrue(it.moveToFirst())
                assertEquals("original history", it.getString(0))
            }
            db.execSQL("DELETE FROM ConversationEntity WHERE id='chat'")
            db.query("SELECT COUNT(*) FROM memory_organization_batch").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        } finally {
            db.close()
        }
    }
}
