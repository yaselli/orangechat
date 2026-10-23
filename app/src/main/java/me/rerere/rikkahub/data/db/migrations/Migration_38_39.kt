package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add request accounting without rewriting any chat, interaction or memory data. */
object Migration_38_39 : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `usage_record` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `created_at` INTEGER NOT NULL,
            `source` TEXT NOT NULL,
            `provider_id` TEXT,
            `model_id` TEXT,
            `model_name` TEXT,
            `assistant_id` TEXT,
            `conversation_id` TEXT,
            `prompt_tokens` INTEGER NOT NULL,
            `completion_tokens` INTEGER NOT NULL,
            `cached_tokens` INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_record_created_at` ON `usage_record` (`created_at`)")
    }
}
