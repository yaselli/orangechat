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
class StableMemoryUpgradeTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun releasedStableAndMemoryDatabasesUpgradeWithoutLosingSourcesOrReactions() {
        for (version in listOf(29, 30, 31, 32, 36, 37, 38, 39, 40, 41, 42)) {
            val name = "stable-memory-upgrade-$version"
            val original = """[{"id":"old"},{"id":"selected","annotations":[{"type":"avatar_interaction","actor":"user","target":"assistant"}]}]"""
            helper.createDatabase(name, version).apply {
                execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at) " +
                    "VALUES('chat','owner','title','[]',1,2)")
                execSQL("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'owner','喜欢吃辣🌶')")
                execSQL("INSERT INTO message_node VALUES('node','chat',0,?,1)", arrayOf(original))
                if (version == 32) execSQL("INSERT INTO message_reaction VALUES(" +
                    "'reaction','chat','selected','user','❤️','原话',1)")
                if (version >= 39) execSQL(
                    "INSERT INTO usage_record(created_at,source,prompt_tokens,completion_tokens,cached_tokens) " +
                        "VALUES(1,'CHAT',42,3,0)")
                if (version == 40) execSQL("UPDATE ConversationEntity SET conversation_config='{" +
                    "\"enableWebSearch\":true}' WHERE id='chat'")
                execSQL("INSERT INTO workspaces(id,name,root,shell_status,created_at,updated_at,tool_approvals) " +
                    "VALUES('local','旧工作区','local-root','READY',1,2,'{\"workspace_shell\":true}')")
                close()
            }
            val db = helper.runMigrationsAndValidate(name, 43, true,
                Migration_29_30, Migration_30_31, Migration_29_32_ChatInteractions,
                Migration_31_33_HistoryMemory, Migration_32_33_StableMemory,
                Migration_33_34, Migration_34_35, Migration_35_36, Migration_36_37, Migration_37_38, Migration_38_39, Migration_39_40, Migration_40_41, Migration_41_42, Migration_42_43)
            try {
                db.query("SELECT messages,select_index FROM message_node WHERE id='node'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(original, it.getString(0))
                    assertEquals(1, it.getInt(1))
                }
                db.query("SELECT id,content FROM MemoryEntity WHERE id=7").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(7, it.getInt(0))
                    assertEquals("喜欢吃辣🌶", it.getString(1))
                }
                if (version == 32) db.query("SELECT emoji,quote FROM message_reaction").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("❤️", it.getString(0))
                    assertEquals("原话", it.getString(1))
                }
                db.query("SELECT COUNT(*) FROM usage_record").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(if (version >= 39) 1 else 0, it.getInt(0))
                }
                db.query("SELECT conversation_config FROM ConversationEntity WHERE id='chat'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(if (version == 40) "{\"enableWebSearch\":true}" else "", it.getString(0))
                }
                db.query("SELECT COUNT(*) FROM translation_history").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
                db.query("SELECT name,shell_status,tool_approvals,type,ssh_config FROM workspaces WHERE id='local'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("旧工作区", it.getString(0))
                    assertEquals("READY", it.getString(1))
                    assertEquals("{\"workspace_shell\":true}", it.getString(2))
                    assertEquals("LOCAL", it.getString(3))
                    assertTrue(it.isNull(4))
                }
                db.query("SELECT COUNT(*) FROM workspaces WHERE type != 'LOCAL' OR ssh_config IS NOT NULL").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
                db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            } finally {
                db.close()
            }
        }
    }
}
