package me.rerere.rikkahub.data.db.fts

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.memory.HistoryMemoryRepository
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class MessageFtsManagerTest {
    private lateinit var database: AppDatabase
    private lateinit var databaseName: String
    private lateinit var db: SupportSQLiteDatabase
    private lateinit var manager: MessageFtsManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = RequerySQLiteOpenHelperFactory(listOf(
            RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                options.customExtensions.add(SQLiteCustomExtension(
                    context.applicationInfo.nativeLibraryDir + "/libsimple", null,
                ))
                options
            }
        ))
        // Requery's in-memory path bypasses ConfigurationOptions and never loads libsimple.
        // Use an isolated temporary file DB, matching the extension/WAL setup of the app.
        databaseName = "message-fts-test-${Uuid.random()}.db"
        database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .openHelperFactory(factory)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.query("SELECT jieba_dict(?)", arrayOf(SimpleDictManager.extractDict(context).absolutePath))
                        .use { assertTrue(it.moveToFirst()) }
                    createMessageFtsTables(db)
                }
            }).build()
        db = database.openHelper.writableDatabase
        manager = MessageFtsManager(database)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::databaseName.isInitialized) {
            InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
        }
    }

    private fun count(table: String): Int = db.query("SELECT COUNT(*) FROM $table").use {
        assertTrue(it.moveToFirst())
        it.getInt(0)
    }

    private fun saveSource(conversation: Conversation) {
        db.execSQL(
            "INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at) VALUES(?,?,?,'[]',1,2)",
            arrayOf(conversation.id.toString(), conversation.assistantId.toString(), conversation.title),
        )
    }

    @Test
    fun legacyIndexIsBackfilledOnceWithoutChangingSourceOrRowids() {
        db.execSQL("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) VALUES('chat','title','original',1,2)")
        db.execSQL("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'owner','retained memory')")
        db.execSQL("DROP TABLE message_fts_rowid")
        db.execSQL("INSERT INTO message_fts(rowid,text,node_id,message_id,conversation_id,title,update_at) " +
            "VALUES(42,'legacy text','node','message','chat','old title',1)")
        repeat(2) { createMessageFtsTables(db) }
        assertEquals(1, count("message_fts_rowid"))
        db.query("SELECT fts_rowid,conversation_id,node_id FROM message_fts_rowid").use {
            assertTrue(it.moveToFirst())
            assertEquals(42L, it.getLong(0))
            assertEquals("chat", it.getString(1))
            assertEquals("node", it.getString(2))
        }
        db.query("SELECT text FROM message_fts WHERE rowid=42").use {
            assertTrue(it.moveToFirst()); assertEquals("legacy text", it.getString(0))
        }
        db.query("SELECT nodes FROM ConversationEntity WHERE id='chat'").use {
            assertTrue(it.moveToFirst()); assertEquals("original", it.getString(0))
        }
        db.query("SELECT content FROM MemoryEntity WHERE id=7").use {
            assertTrue(it.moveToFirst()); assertEquals("retained memory", it.getString(0))
        }
    }

    @Test
    fun reindexOnlyTouchesChangedNodesAndDeletionIsConversationScoped() = runBlocking {
        val node = MessageNode(messages = listOf(UIMessage.user("original apple")))
        val untouched = MessageNode(messages = listOf(UIMessage.user("unchanged banana")))
        val first = Conversation(assistantId = Uuid.random(), title = "first", messageNodes = listOf(node, untouched))
        // Deliberately reuse a node ID: updates must still be scoped to a conversation.
        val other = first.copy(id = Uuid.random(), title = "other", messageNodes = listOf(node))
        saveSource(first); saveSource(other)
        manager.indexConversation(first); manager.indexConversation(other)
        assertEquals(3, count("message_fts"))
        val unchangedRowid = db.query("SELECT rowid FROM message_fts WHERE node_id=?", arrayOf(untouched.id.toString()))
            .use { assertTrue(it.moveToFirst()); it.getLong(0) }
        val changed = node.copy(messages = listOf(UIMessage.user("replacement cherry")))
        manager.reindexNodes(first.id.toString(), setOf(node.id.toString()), listOf(changed, untouched))
        assertEquals(3, count("message_fts"))
        assertEquals(3, count("message_fts_rowid"))
        db.query("SELECT text FROM message_fts WHERE rowid=?", arrayOf(unchangedRowid)).use {
            assertTrue(it.moveToFirst()); assertEquals("unchanged banana", it.getString(0))
        }
        manager.reindexNodes(first.id.toString(), setOf(node.id.toString()), listOf(untouched))
        assertEquals(2, count("message_fts"))
        manager.deleteConversation(first.id.toString())
        assertEquals(1, count("message_fts"))
        assertEquals(1, count("message_fts_rowid"))
        db.query("SELECT conversation_id FROM message_fts").use {
            assertTrue(it.moveToFirst()); assertEquals(other.id.toString(), it.getString(0))
        }
        manager.deleteAll()
        assertEquals(0, count("message_fts")); assertEquals(0, count("message_fts_rowid"))
    }

    @Test
    fun keywordRepairKeepsAlternativeBranchesAndMappingConsistent() = runBlocking {
        val node = MessageNode(messages = listOf(UIMessage.user("selected apple"), UIMessage.user("alternate banana")))
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))
        saveSource(conversation)
        val original = JsonInstant.encodeToString(node.messages)
        db.execSQL("INSERT INTO message_node VALUES(?,?,0,?,0)",
            arrayOf(node.id.toString(), conversation.id.toString(), original))
        manager.indexConversation(conversation)
        val repository = HistoryMemoryRepository(database)
        repeat(2) {
            assertEquals(1, repository.refreshKeywords(conversation.assistantId.toString()))
            assertEquals(2, count("message_fts")); assertEquals(2, count("message_fts_rowid"))
            assertEquals(1, manager.search("apple").size)
            assertEquals(1, manager.search("banana").size)
        }
        db.query("SELECT messages FROM message_node").use {
            assertTrue(it.moveToFirst()); assertEquals(original, it.getString(0))
        }
        manager.deleteConversation(conversation.id.toString())
        assertEquals(0, count("message_fts")); assertEquals(0, count("message_fts_rowid"))
    }

    @Test
    fun searchUsesCurrentConversationMetadataAndIndexOperationsRollBackTogether() = runBlocking {
        val conversation = Conversation(assistantId = Uuid.random(), title = "before",
            messageNodes = listOf(MessageNode(messages = listOf(UIMessage.user("unique apple")))))
        saveSource(conversation)
        manager.indexConversation(conversation)
        db.execSQL("UPDATE ConversationEntity SET title='after',update_at=12345 WHERE id=?",
            arrayOf(conversation.id.toString()))
        val result = manager.search("apple").single()
        assertEquals("after", result.title)
        assertEquals(12345L, result.updateAt.toEpochMilli())
        try {
            database.runInTransaction(Runnable {
                deleteMessageFtsConversation(db, conversation.id.toString())
                error("rollback")
            })
            fail("Expected rollback")
        } catch (_: IllegalStateException) {
            assertEquals(1, count("message_fts")); assertEquals(1, count("message_fts_rowid"))
        }
        db.query("EXPLAIN QUERY PLAN SELECT fts_rowid FROM message_fts_rowid WHERE conversation_id=? AND node_id=?",
            arrayOf(conversation.id.toString(), conversation.messageNodes.single().id.toString())).use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getString(3).contains("index_message_fts_rowid_conversation_node"))
        }
    }
}
