package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_36_37 : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Additive only. Legacy JSON vectors remain usable, with no embedding API call during upgrade.
        db.execSQL("ALTER TABLE memory_vector ADD COLUMN vectorBytes BLOB")
        db.execSQL("ALTER TABLE history_memory_vector ADD COLUMN vectorBytes BLOB")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_history_memory_vector_scope_namespace_sliceId ON history_memory_vector(scope, namespace, sliceId)")
    }
}
