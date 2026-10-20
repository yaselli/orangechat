package me.rerere.rikkahub.data.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
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

data class HistoryVectorProjection(
    @Embedded val record: HistoryMemoryVector,
    val selectedRole: String,
    val selectedCreatedAt: String,
    val selectedNodeIndex: Int,
)

@Dao
interface HistoryMemoryDAO {
    @Query("SELECT * FROM history_memory_vector WHERE scope=:scope AND vectorBytes IS NULL AND vector!='' ORDER BY namespace,sliceId LIMIT 32")
    suspend fun legacyVectors(scope: String): List<HistoryMemoryVector>

    @Query("UPDATE history_memory_vector SET vector='',vectorBytes=:packed WHERE namespace=:namespace AND sliceId=:sliceId AND vector=:legacy AND vectorBytes IS NULL")
    suspend fun packLegacy(namespace: String, sliceId: String, legacy: String, packed: ByteArray)

    // Only selected-message metadata is extracted here; original text is fetched for ranked candidates later.
    @Query("""SELECT v.*,
        json_extract(n.messages, '$[' || n.select_index || '].role') AS selectedRole,
        json_extract(n.messages, '$[' || n.select_index || '].createdAt') AS selectedCreatedAt,
        n.node_index AS selectedNodeIndex
        FROM history_memory_vector v
        JOIN message_node n ON n.id = v.nodeId AND n.conversation_id = v.conversationId
        JOIN conversationentity c ON c.id = n.conversation_id AND c.assistant_id = v.scope
        WHERE v.scope = :scope AND v.namespace = :namespace AND v.sliceId > :after
        AND json_valid(n.messages) AND n.select_index >= 0
        AND json_extract(n.messages, '$[' || n.select_index || '].id') = v.messageId
        AND json_extract(n.messages, '$[' || n.select_index || '].role') IN ('user','assistant')
        AND json_extract(n.messages, '$[' || n.select_index || '].createdAt') <= :latestTime
        AND (n.conversation_id != :conversationId OR n.node_index < :maxNodeIndex)
        ORDER BY v.sliceId LIMIT 128""")
    suspend fun vectorPage(
        scope: String, namespace: String, after: String, conversationId: String, maxNodeIndex: Int, latestTime: String,
    ): List<HistoryVectorProjection>

    @RawQuery
    suspend fun sources(query: SupportSQLiteQuery): List<HistoryMemorySource>

    @Query("""SELECT v.* FROM history_memory_vector v
        JOIN message_node n ON n.id = v.nodeId AND n.conversation_id = v.conversationId
        JOIN conversationentity c ON c.id = n.conversation_id AND c.assistant_id = v.scope
        WHERE v.scope = :scope AND v.namespace = :namespace AND v.sliceId > :after
        AND json_valid(n.messages) AND n.select_index >= 0
        AND json_extract(n.messages, '$[' || n.select_index || '].id') = v.messageId
                AND json_extract(n.messages, '$[' || n.select_index || '].role') IN ('user','assistant')
        AND json_extract(n.messages, '$[' || n.select_index || '].createdAt') <= :latestTime
        AND (n.conversation_id != :conversationId OR n.node_index < :maxNodeIndex)
        ORDER BY v.sliceId LIMIT 64""")
    suspend fun vectors(
        scope: String, namespace: String, after: String, conversationId: String, maxNodeIndex: Int, latestTime: String,
    ): List<HistoryMemoryVector>

    @Query("SELECT sliceId FROM history_memory_vector WHERE scope = :scope AND namespace = :namespace")
    suspend fun indexedIds(scope: String, namespace: String): List<String>

    @Query("SELECT DISTINCT nodeId FROM history_memory_vector WHERE scope=:scope AND nodeId>:after ORDER BY nodeId LIMIT 64")
    suspend fun indexedNodes(scope: String, after: String): List<String>

    @Query("DELETE FROM history_memory_vector WHERE scope=:scope AND nodeId IN (:nodeIds)")
    suspend fun removeNodes(scope: String, nodeIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(vectors: List<HistoryMemoryVector>)

    @Query("DELETE FROM history_memory_vector WHERE scope = :scope AND namespace != :namespace")
    suspend fun removeOtherModels(scope: String, namespace: String)
}
