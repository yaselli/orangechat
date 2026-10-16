package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_31_32 : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS message_reaction (
            id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, messageId TEXT NOT NULL,
            actor TEXT NOT NULL, emoji TEXT NOT NULL, quote TEXT NOT NULL, createdAt INTEGER NOT NULL,
            FOREIGN KEY(conversationId) REFERENCES ConversationEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_message_reaction_conversationId
            ON message_reaction(conversationId)""")
    }
}
