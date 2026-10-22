package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_34_35 : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS memory_graph (
            memoryId INTEGER NOT NULL, owner TEXT NOT NULL, scope TEXT NOT NULL,
            revision INTEGER NOT NULL, sourceHash TEXT NOT NULL, relation TEXT NOT NULL,
            quote TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(owner, memoryId),
            FOREIGN KEY(memoryId) REFERENCES MemoryEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_owner_scope ON memory_graph(owner, scope)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_memoryId ON memory_graph(memoryId)")
    }
}
