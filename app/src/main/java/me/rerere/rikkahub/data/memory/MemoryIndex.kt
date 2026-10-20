package me.rerere.rikkahub.data.memory

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity

@Entity(
    tableName = "memory_vector",
    primaryKeys = ["namespace", "sliceId"],
    foreignKeys = [ForeignKey(
        entity = MemoryEntity::class, parentColumns = ["id"], childColumns = ["memoryId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("memoryId"), Index(value = ["scope", "namespace"])],
)
data class MemoryVectorEntity(
    val namespace: String,
    val sliceId: String,
    val scope: String,
    val memoryId: Int,
    val revision: Long,
    val sourceHash: String,
    val vector: String,
    val vectorBytes: ByteArray? = null,
)

@Entity(
    tableName = "memory_recall_snapshot",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId"), Index("createdAt")],
)
data class MemoryRecallSnapshotEntity(
    @PrimaryKey val key: String,
    val conversationId: String,
    val scope: String,
    val messageId: String,
    val messageHash: String,
    val excerpts: String,
    val createdAt: Long,
)
