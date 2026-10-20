package me.rerere.rikkahub.data.db.migrations

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A behavior rollback keeps the already-installed schema: it must open v38 without a downgrade. */
@RunWith(AndroidJUnit4::class)
class StableMemoryRollbackTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun installedV38OpensPreservingOriginalMessagesAndInertMemoryData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "stable-memory-behavior-rollback"
        context.deleteDatabase(name)
        val selected = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("拍了拍，原话🌶")),
            annotations = listOf(UIMessageAnnotation.AvatarInteraction(actor = "user", target = "assistant")),
            localMetadata = buildJsonObject { put("memory_recall", "retained local diagnostics") },
        )
        val original = JsonInstant.encodeToString(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()), selected))
        helper.createDatabase(name, 38).apply {
            execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at,context_compaction) " +
                "VALUES('chat','owner','title','[]',1,2,'retained summary metadata')")
            execSQL("INSERT INTO message_node VALUES('node','chat',0,?,1)", arrayOf(original))
            execSQL("INSERT INTO MemoryEntity(id,assistant_id,content,source_kind,revision) " +
                "VALUES(7,'owner','喜欢吃辣🌶','automatic',3)")
            execSQL("INSERT INTO memory_vector VALUES('model','slice','owner',7,3,'hash','[1,0]',X'0102')")
            execSQL("INSERT INTO history_memory_vector VALUES(" +
                "'model','history','owner','chat','node',?,'hash',0,2,'[1,0]',X'0304')", arrayOf(selected.id.toString()))
            execSQL("INSERT INTO memory_index_attempt VALUES('owner','model','slice','batch','done','',1)")
            execSQL("INSERT INTO memory_organization_batch VALUES('batch','owner','owner','chat','[]','done','',1)")
            execSQL("INSERT INTO memory_evidence VALUES(7,'owner','chat','node',?,'hash','user','time','原话')",
                arrayOf(selected.id.toString()))
            execSQL("INSERT INTO memory_recall_snapshot VALUES('snapshot','chat','owner',?,'hash','[]',1)",
                arrayOf(selected.id.toString()))
            execSQL("INSERT INTO message_reaction VALUES('reaction','chat',?,'user','❤️','原话',1)",
                arrayOf(selected.id.toString()))
            close()
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(38, sqlite.version)
            sqlite.query("SELECT messages,select_index FROM message_node WHERE id='node'").use {
                assertTrue(it.moveToFirst())
                assertEquals(original, it.getString(0))
                val messages = JsonInstant.decodeFromString<List<UIMessage>>(it.getString(0))
                assertEquals(selected, messages[it.getInt(1)])
            }
            for (table in listOf("memory_vector", "history_memory_vector", "memory_index_attempt",
                "memory_organization_batch", "memory_evidence", "memory_recall_snapshot", "message_reaction")) {
                sqlite.query("SELECT COUNT(*) FROM $table").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(table, 1, it.getInt(0))
                }
            }
            assertEquals("喜欢吃辣🌶", MemoryRepository(db.memoryDao()).getMemoriesOfAssistant("owner").single().content)
            MemoryRepository(db.memoryDao()).updateContent(7, "依旧喜欢吃辣🌶")
            assertEquals("依旧喜欢吃辣🌶", db.memoryDao().getMemoryById(7)?.content)
            assertEquals(3L, db.memoryDao().getMemoryById(7)?.revision)
            sqlite.query("SELECT context_compaction FROM ConversationEntity WHERE id='chat'").use {
                assertTrue(it.moveToFirst())
                assertEquals("retained summary metadata", it.getString(0))
            }
            sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
