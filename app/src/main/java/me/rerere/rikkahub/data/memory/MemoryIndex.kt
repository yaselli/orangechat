package me.rerere.rikkahub.data.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
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

@Dao
interface MemoryIndexDAO {
    @Query("SELECT * FROM memory_vector WHERE scope = :scope AND namespace = :namespace")
    suspend fun vectors(scope: String, namespace: String): List<MemoryVectorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putVectors(vectors: List<MemoryVectorEntity>)

    // Keep only one model's derived vectors per scope. Original memory content is never touched.
    @Query("DELETE FROM memory_vector WHERE scope = :scope AND namespace != :namespace")
    suspend fun removeOtherModels(scope: String, namespace: String)

    @Query("DELETE FROM memory_vector WHERE scope = :scope AND namespace NOT IN (:namespaces)")
    suspend fun removeUnusedModels(scope: String, namespaces: List<String>)

    @Query("""DELETE FROM memory_vector WHERE scope = :scope AND (
        NOT EXISTS (SELECT 1 FROM memoryentity m WHERE m.id = memory_vector.memoryId
            AND m.assistant_id = memory_vector.scope AND m.revision = memory_vector.revision))""")
    suspend fun removeStaleVectors(scope: String)

    @Query("""DELETE FROM memory_vector WHERE memoryId = :memoryId
        AND (revision != :revision OR sourceHash != :sourceHash)""")
    suspend fun removeStaleSource(memoryId: Int, revision: Long, sourceHash: String)

    @Query("SELECT * FROM memory_recall_snapshot WHERE `key` = :key")
    suspend fun snapshot(key: String): MemoryRecallSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putSnapshot(snapshot: MemoryRecallSnapshotEntity)

    // Bounded derived storage for retries, not another copy of the conversation archive.
    @Query("""DELETE FROM memory_recall_snapshot WHERE `key` IN
        (SELECT `key` FROM memory_recall_snapshot ORDER BY createdAt DESC, `key` ASC LIMIT -1 OFFSET 1024)""")
    suspend fun trimSnapshots()
}
