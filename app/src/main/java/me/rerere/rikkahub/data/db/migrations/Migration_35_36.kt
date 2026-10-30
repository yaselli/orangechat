package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_35_36 : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS memory_graph")
        db.execSQL("""CREATE TABLE IF NOT EXISTS memory_index_attempt (
            owner TEXT NOT NULL, namespace TEXT NOT NULL, sliceId TEXT NOT NULL,
            batchId TEXT NOT NULL, state TEXT NOT NULL, detail TEXT NOT NULL, createdAt INTEGER NOT NULL,
            PRIMARY KEY(owner, namespace, sliceId))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_index_attempt_owner_createdAt ON memory_index_attempt(owner, createdAt)")
    }
}
