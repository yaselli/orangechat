package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_33_34 : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // SQLite resolves names case-insensitively, but Room compares foreign-key metadata exactly.
        db.execSQL("""CREATE TABLE IF NOT EXISTS memory_organization_batch (
            id TEXT NOT NULL, owner TEXT NOT NULL, scope TEXT NOT NULL, conversationId TEXT NOT NULL,
            messageIds TEXT NOT NULL, state TEXT NOT NULL, detail TEXT NOT NULL, createdAt INTEGER NOT NULL,
            PRIMARY KEY(id),
            FOREIGN KEY(conversationId) REFERENCES ConversationEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_memory_organization_batch_conversationId
            ON memory_organization_batch(conversationId)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_memory_organization_batch_owner
            ON memory_organization_batch(owner)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS memory_evidence (
            memoryId INTEGER NOT NULL, owner TEXT NOT NULL, conversationId TEXT NOT NULL,
            nodeId TEXT NOT NULL, messageId TEXT NOT NULL, sourceHash TEXT NOT NULL,
            role TEXT NOT NULL, sourceCreatedAt TEXT NOT NULL, quote TEXT NOT NULL,
            PRIMARY KEY(memoryId),
            FOREIGN KEY(memoryId) REFERENCES MemoryEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
    }
}
