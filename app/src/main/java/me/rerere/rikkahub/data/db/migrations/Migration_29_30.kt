package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive migration: keep every old memory id, owner, content and all conversation nodes unchanged. */
object Migration_29_30 : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversationentity ADD COLUMN context_compaction TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN source_kind TEXT NOT NULL DEFAULT 'legacy'")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN source_conversation_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN source_message_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
    }
}
