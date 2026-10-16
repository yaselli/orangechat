package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v32 belongs to stable chat interactions. This debug path never introduces that feature. */
object Migration_31_33_HistoryMemory : Migration(31, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS history_memory_vector (
            namespace TEXT NOT NULL, sliceId TEXT NOT NULL, scope TEXT NOT NULL,
            conversationId TEXT NOT NULL, nodeId TEXT NOT NULL, messageId TEXT NOT NULL,
            sourceHash TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, vector TEXT NOT NULL,
            PRIMARY KEY(namespace, sliceId),
            FOREIGN KEY(nodeId) REFERENCES message_node(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_history_memory_vector_nodeId ON history_memory_vector(nodeId)")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_history_memory_vector_scope_namespace
            ON history_memory_vector(scope, namespace)""")
    }
}
