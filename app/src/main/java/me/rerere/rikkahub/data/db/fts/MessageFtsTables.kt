package me.rerere.rikkahub.data.db.fts

import androidx.sqlite.db.SupportSQLiteDatabase

/** Non-Room search cache tables. Source chats and memory tables are never rewritten here. */
internal fun createMessageFtsTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
            text,
            node_id UNINDEXED,
            message_id UNINDEXED,
            conversation_id UNINDEXED,
            title UNINDEXED,
            update_at UNINDEXED,
            tokenize = 'simple'
        )
        """.trimIndent()
    )
    val hasMapping = db.query(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'message_fts_rowid'"
    ).use { it.moveToFirst() }
    if (hasMapping) return

    db.beginTransaction()
    try {
        db.execSQL(
            "CREATE TABLE message_fts_rowid(fts_rowid INTEGER PRIMARY KEY, conversation_id TEXT NOT NULL, node_id TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX index_message_fts_rowid_conversation_node ON message_fts_rowid(conversation_id, node_id)"
        )
        // One scan on first upgrade; preserve rowids and the already-tokenized message text.
        db.execSQL(
            "INSERT INTO message_fts_rowid(fts_rowid, conversation_id, node_id) SELECT rowid, conversation_id, node_id FROM message_fts"
        )
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

/** Must be called in the same transaction as its FTS INSERT. */
internal fun recordMessageFtsRow(db: SupportSQLiteDatabase, conversationId: String, nodeId: String) {
    db.execSQL(
        "INSERT INTO message_fts_rowid(fts_rowid, conversation_id, node_id) VALUES (last_insert_rowid(), ?, ?)",
        arrayOf(conversationId, nodeId),
    )
}

/** Rowid lookups avoid scanning the UNINDEXED columns of the entire FTS virtual table. */
internal fun deleteMessageFtsNode(db: SupportSQLiteDatabase, conversationId: String, nodeId: String) {
    db.execSQL(
        "DELETE FROM message_fts WHERE rowid IN (SELECT fts_rowid FROM message_fts_rowid WHERE conversation_id = ? AND node_id = ?)",
        arrayOf(conversationId, nodeId),
    )
    db.execSQL(
        "DELETE FROM message_fts_rowid WHERE conversation_id = ? AND node_id = ?",
        arrayOf(conversationId, nodeId),
    )
}

internal fun deleteMessageFtsConversation(db: SupportSQLiteDatabase, conversationId: String) {
    db.execSQL(
        "DELETE FROM message_fts WHERE rowid IN (SELECT fts_rowid FROM message_fts_rowid WHERE conversation_id = ?)",
        arrayOf(conversationId),
    )
    db.execSQL("DELETE FROM message_fts_rowid WHERE conversation_id = ?", arrayOf(conversationId))
}

/** Keyword repair replaces the selected message only; alternative branches keep their rows. */
internal fun deleteMessageFtsMessage(
    db: SupportSQLiteDatabase,
    conversationId: String,
    nodeId: String,
    messageId: String,
) {
    val rowids = db.query(
        """
        SELECT f.rowid FROM message_fts_rowid AS r JOIN message_fts AS f ON f.rowid = r.fts_rowid
        WHERE r.conversation_id = ? AND r.node_id = ? AND f.message_id = ?
        """.trimIndent(),
        arrayOf(conversationId, nodeId, messageId),
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
    }
    rowids.forEach { rowid ->
        db.execSQL("DELETE FROM message_fts WHERE rowid = ?", arrayOf(rowid))
        db.execSQL("DELETE FROM message_fts_rowid WHERE fts_rowid = ?", arrayOf(rowid))
    }
}
