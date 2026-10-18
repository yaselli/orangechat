package me.rerere.rikkahub.data.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.MemoryEntity

@Entity(
    tableName = "memory_graph",
    primaryKeys = ["owner", "memoryId"],
    foreignKeys = [ForeignKey(entity = MemoryEntity::class, parentColumns = ["id"], childColumns = ["memoryId"],
        onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["owner", "scope"]), Index("memoryId")],
)
data class MemoryGraphRecord(
    val memoryId: Int,
    val owner: String,
    val scope: String,
    val revision: Long,
    val sourceHash: String,
    val relation: String,
    val quote: String,
    val createdAt: Long,
)

@Dao
interface MemoryGraphDAO {
    @Query("""SELECT e.* FROM memory_evidence e JOIN memory_graph g ON g.memoryId=e.memoryId
        JOIN MemoryEntity m ON m.id=e.memoryId
        WHERE g.owner=:owner AND g.scope=:scope
        AND m.assistant_id=:scope AND m.revision=g.revision AND m.revision=1 AND m.source_kind='automatic'""")
    suspend fun evidence(owner: String, scope: String): List<MemoryEvidence>

    @Query("""SELECT g.* FROM memory_graph g JOIN MemoryEntity m ON m.id=g.memoryId
        WHERE g.owner=:owner AND g.scope=:scope AND m.assistant_id=g.scope AND m.revision=g.revision""")
    suspend fun records(owner: String, scope: String): List<MemoryGraphRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: MemoryGraphRecord)
}
