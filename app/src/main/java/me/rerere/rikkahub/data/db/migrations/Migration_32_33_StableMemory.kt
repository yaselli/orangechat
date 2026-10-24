package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Stable v32 is v29 plus message_reaction, not the experimental memory v31 schema. */
object Migration_32_33_StableMemory : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Migration_29_30.migrate(db)
        Migration_30_31.migrate(db)
        Migration_31_33_HistoryMemory.migrate(db)
    }
}
