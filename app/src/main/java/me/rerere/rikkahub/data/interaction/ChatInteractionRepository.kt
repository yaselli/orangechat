package me.rerere.rikkahub.data.interaction

import androidx.room.withTransaction
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

class ChatInteractionRepository(
    private val db: AppDatabase,
    private val conversations: ConversationRepository,
) {
    private val dao get() = db.messageReactionDao()
    fun observe(conversationId: String) = dao.observe(conversationId)

    suspend fun deleteForMessage(conversationId: Uuid, messageId: Uuid) =
        dao.deleteForMessage(conversationId.toString(), messageId.toString())

    suspend fun copyForFork(from: Uuid, to: Uuid, messageIds: Set<String>) = db.withTransaction {
        dao.list(from.toString()).filter { it.messageId in messageIds }.forEach {
            dao.insert(it.copy(
                id = reactionKey(to.toString(), it.messageId, it.actor, it.emoji, it.quote),
                conversationId = to.toString(),
            ))
        }
    }

    suspend fun react(
        conversationId: Uuid, assistant: Assistant, target: String, actor: String, emoji: String,
        add: Boolean? = null, quote: String = "",
    ) = db.withTransaction {
        require(actor == "user" || actor == "assistant")
        require(emoji in CHAT_REACTIONS) { "请选择支持的表情或颜文字" }
        val conversation = conversations.getConversationById(conversationId) ?: error("聊天已删除")
        require(conversation.assistantId == assistant.id) { "不能操作其他助手的聊天" }
        val message = resolveReactionTarget(conversation.currentMessages, assistant, target, quote)
        val resolvedQuote = resolveReactionQuote(message, assistant, quote)
        require(resolvedQuote.length <= 48000) { "该段内容过长，暂不支持贴表情" }
        val key = reactionKey(conversationId.toString(), message.id.toString(), actor, emoji, resolvedQuote)
        val shouldAdd = add ?: !dao.exists(key)
        if (shouldAdd) dao.insert(MessageReaction(
            key, conversationId.toString(), message.id.toString(), actor, emoji, resolvedQuote,
            System.currentTimeMillis(),
        )) else dao.delete(key)
    }

    /** Snapshot once; receipt metadata is persisted only with a completed model decision. */
    internal suspend fun prepare(
        sourceMessages: List<UIMessage>, conversationId: String?, assistant: Assistant,
    ): ReactionContextTurn {
        if (conversationId == null || !assistant.enableMessageReactions) {
            return prepareReactionContext(sourceMessages, emptyList(), enabled = false)
        }
        val reactions = dao.list(conversationId)
        val visible = sourceMessages.associateBy { it.id.toString() }
        val active = reactions.filter { reaction ->
            val source = visible[reaction.messageId] ?: return@filter false
            val segments = reactionSegments(source, assistant)
            if (reaction.quote.isEmpty()) segments.isEmpty() else reaction.quote in segments
        }
        return prepareReactionContext(sourceMessages, active, enabled = true)
    }
}
