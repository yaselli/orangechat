/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    // Existing records have no recoverable source: never manufacture provenance during migration.
    @ColumnInfo("source_kind", defaultValue = "'legacy'")
    val sourceKind: String = "legacy",
    @ColumnInfo("source_conversation_id", defaultValue = "''")
    val sourceConversationId: String = "",
    @ColumnInfo("source_message_id", defaultValue = "''")
    val sourceMessageId: String = "",
    @ColumnInfo("revision", defaultValue = "1")
    val revision: Long = 1,
    @ColumnInfo("created_at", defaultValue = "0")
    val createdAt: Long = 0,
    @ColumnInfo("updated_at", defaultValue = "0")
    val updatedAt: Long = 0,
)

/** Legacy summary records have their own management path and must not become individual fact memories. */
fun MemoryEntity.isSummaryMemory(): Boolean = content.startsWith("[daily_summary]") ||
    content.startsWith("[phase_summary]") || content.startsWith("[auto_summary]")
