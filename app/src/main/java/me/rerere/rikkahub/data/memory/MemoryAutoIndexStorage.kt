package me.rerere.rikkahub.data.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Durable attempted slice IDs prevent regeneration, process death or a failed call from billing the same batch again. */
@Entity(tableName = "memory_index_attempt", primaryKeys = ["owner", "namespace", "sliceId"],
    indices = [Index(value = ["owner", "createdAt"])])
data class MemoryIndexAttempt(
    val owner: String, val namespace: String, val sliceId: String, val batchId: String,
    val state: String, val detail: String, val createdAt: Long,
)

@Dao
interface MemoryAutoIndexDAO {
    @Query("SELECT sliceId FROM memory_index_attempt WHERE owner=:owner AND namespace=:namespace")
    suspend fun attempted(owner: String, namespace: String): List<String>
    @Query("SELECT COUNT(DISTINCT batchId) FROM memory_index_attempt WHERE owner=:owner AND createdAt>=:since")
    suspend fun batchesSince(owner: String, since: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claim(rows: List<MemoryIndexAttempt>): List<Long>
    @Query("UPDATE memory_index_attempt SET state=:state,detail=:detail WHERE owner=:owner AND batchId=:batchId")
    suspend fun finish(owner: String, batchId: String, state: String, detail: String)
    @Query("SELECT * FROM memory_index_attempt WHERE owner=:owner ORDER BY createdAt DESC LIMIT 1")
    fun latest(owner: String): Flow<MemoryIndexAttempt?>
}
