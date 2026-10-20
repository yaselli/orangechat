package me.rerere.rikkahub.data.memory

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity

@Entity(
    tableName = "history_memory_vector",
    primaryKeys = ["namespace", "sliceId"],
    foreignKeys = [ForeignKey(
        entity = MessageNodeEntity::class, parentColumns = ["id"], childColumns = ["nodeId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("nodeId"), Index(value = ["scope", "namespace"]), Index(value = ["scope", "namespace", "sliceId"])],
)
data class HistoryMemoryVector(
    val namespace: String,
    val sliceId: String,
    val scope: String,
    val conversationId: String,
    val nodeId: String,
    val messageId: String,
    val sourceHash: String,
    val start: Int,
    val end: Int,
    val vector: String,
    val vectorBytes: ByteArray? = null,
)
