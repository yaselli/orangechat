package me.rerere.rikkahub.data.interaction

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

@Entity(
    tableName = "message_reaction",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId")],
)
data class MessageReaction(
    @PrimaryKey val id: String,
    val conversationId: String,
    val messageId: String,
    val actor: String,
    val emoji: String,
    val quote: String,
    val createdAt: Long,
)

@Dao
interface MessageReactionDAO {
    @Query("SELECT * FROM message_reaction WHERE conversationId = :conversationId ORDER BY createdAt, id")
    fun observe(conversationId: String): Flow<List<MessageReaction>>

    @Query("SELECT * FROM message_reaction WHERE conversationId = :conversationId ORDER BY createdAt, id")
    suspend fun list(conversationId: String): List<MessageReaction>

    @Query("SELECT EXISTS(SELECT 1 FROM message_reaction WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(reaction: MessageReaction)

    @Query("DELETE FROM message_reaction WHERE conversationId = :conversationId AND messageId = :messageId")
    suspend fun deleteForMessage(conversationId: String, messageId: String)

    @Query("DELETE FROM message_reaction WHERE id = :id")
    suspend fun delete(id: String)
}
