package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Only derived indexes and recall snapshots; source messages and memories are unchanged. */
object Migration_30_31 : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Pre-v22 releases used this name for a different, derived vector cache. Some upgraded
        // databases retain it. v30 has no Room-owned memory_vector table, so rebuild only that
        // cache before introducing the v31 schema. MemoryEntity, memory_bank (including its
        // embedding column), conversations and messages remain untouched. Room wraps this in
        // the upgrade transaction, so a later migration failure also rolls this replacement back.
        db.execSQL("DROP TABLE IF EXISTS memory_vector")
        db.execSQL("""CREATE TABLE IF NOT EXISTS memory_vector (
            namespace TEXT NOT NULL, sliceId TEXT NOT NULL, scope TEXT NOT NULL,
            memoryId INTEGER NOT NULL, revision INTEGER NOT NULL, sourceHash TEXT NOT NULL,
            vector TEXT NOT NULL, PRIMARY KEY(namespace, sliceId),
            FOREIGN KEY(memoryId) REFERENCES MemoryEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_vector_memoryId ON memory_vector(memoryId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_vector_scope_namespace ON memory_vector(scope, namespace)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS memory_recall_snapshot (
            `key` TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, scope TEXT NOT NULL,
            messageId TEXT NOT NULL, messageHash TEXT NOT NULL, excerpts TEXT NOT NULL, createdAt INTEGER NOT NULL,
            FOREIGN KEY(conversationId) REFERENCES ConversationEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_memory_recall_snapshot_conversationId
            ON memory_recall_snapshot(conversationId)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_recall_snapshot_createdAt ON memory_recall_snapshot(createdAt)")
    }
}
