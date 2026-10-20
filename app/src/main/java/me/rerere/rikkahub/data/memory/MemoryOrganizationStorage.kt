package me.rerere.rikkahub.data.memory

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity

/** A durable paid-call claim, including failed/interrupted batches; never automatically retried. */
@Entity(
    tableName = "memory_organization_batch",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId"), Index("owner")],
)
data class MemoryOrganizationBatch(
    @PrimaryKey val id: String,
    val owner: String,
    val scope: String,
    val conversationId: String,
    val messageIds: String,
    val state: String,
    val detail: String,
    val createdAt: Long,
)

/** Immutable original evidence for revision 1. Later manual/tool edits must not pretend it proves the new content. */
@Entity(
    tableName = "memory_evidence",
    foreignKeys = [ForeignKey(
        entity = MemoryEntity::class, parentColumns = ["id"], childColumns = ["memoryId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MemoryEvidence(
    @PrimaryKey val memoryId: Int,
    val owner: String,
    val conversationId: String,
    val nodeId: String,
    val messageId: String,
    val sourceHash: String,
    val role: String,
    val sourceCreatedAt: String,
    val quote: String,
)
