package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Unify the memory and stable interaction schemas; existing reactions remain untouched. */
object Migration_37_38 : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Migration_29_32_ChatInteractions.migrate(db)
    }
}
