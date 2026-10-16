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
class Migration_31_32_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationPreservesHistoryAndValidatesReactionTable() {
        val name = "chat-interaction-31-32"
        helper.createDatabase(name, 31).apply {
            execSQL("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) " +
                "VALUES('chat','title','[]',1,2)")
            execSQL("INSERT INTO message_node VALUES('node','chat',0,'original',0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 32, true, Migration_31_32)
        try {
            db.query("SELECT messages FROM message_node").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("original", cursor.getString(0))
            }
            db.execSQL("INSERT INTO message_reaction VALUES('key','chat','message','user','❤️','喜欢牛奶',1)")
            db.query("SELECT emoji,quote FROM message_reaction").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("❤️", cursor.getString(0))
                assertEquals("喜欢牛奶", cursor.getString(1))
            }
        } finally { db.close() }
    }
}
