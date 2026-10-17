package me.rerere.rikkahub.data.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
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

@Dao
interface MemoryOrganizationDAO {
    @Query("SELECT * FROM memory_organization_batch WHERE owner = :owner AND conversationId = :conversationId")
    suspend fun batches(owner: String, conversationId: String): List<MemoryOrganizationBatch>

    @Query("SELECT * FROM memory_organization_batch WHERE owner = :owner ORDER BY createdAt DESC, id DESC LIMIT 1")
    fun latest(owner: String): Flow<MemoryOrganizationBatch?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claim(batch: MemoryOrganizationBatch): Long

    @Query("UPDATE memory_organization_batch SET state = :state, detail = :detail WHERE id = :id")
    suspend fun finish(id: String, state: String, detail: String)

    @Insert
    suspend fun evidence(value: MemoryEvidence)

    @Query("SELECT e.* FROM memory_evidence e JOIN memoryentity m ON e.memoryId = m.id " +
        "WHERE e.memoryId = :id AND m.assistant_id = :scope AND m.revision = 1 AND m.source_kind = 'automatic'")
    suspend fun evidence(id: Int, scope: String): MemoryEvidence?
}
