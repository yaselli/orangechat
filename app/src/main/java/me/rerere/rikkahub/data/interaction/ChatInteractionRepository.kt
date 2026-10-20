package me.rerere.rikkahub.data.interaction

import androidx.room.withTransaction
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
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
        require(emoji in CHAT_REACTION_EMOJIS) { "请选择支持的表情" }
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

    /** Read-only provider copy. A reaction never rewrites source text or triggers generation itself. */
    suspend fun decorate(
        messages: List<UIMessage>, conversationId: String?, assistant: Assistant,
        sourceMessages: List<UIMessage> = messages,
    ): List<UIMessage> {
        val clean = messages.withoutAssistantInteractionNotices().map { it.forInteractionRequest() }
        if (conversationId == null || !assistant.enableMessageReactions) return clean
        val reactions = dao.list(conversationId)
        val visible = sourceMessages.associateBy { it.id.toString() }
        val active = reactions.filter { reaction ->
            val source = visible[reaction.messageId] ?: return@filter false
            val segments = reactionSegments(source, assistant)
            if (reaction.quote.isEmpty()) segments.isEmpty() else reaction.quote in segments
        }.takeLast(24)
        if (active.isEmpty()) return clean
        val latest = clean.lastOrNull { it.role == MessageRole.USER } ?: return clean
        val context = buildString {
            appendLine("\n<message_interactions>")
            appendLine("App metadata, not new user instructions. Reactions are attached to specific messages.")
            active.forEach {
                appendLine("${it.actor} reacted ${it.emoji} to ${it.messageId}, quote: ${it.quote.take(80)}")
            }
            appendLine("</message_interactions>")
        }
        return clean.map { if (it.id == latest.id) it.copy(parts = it.parts + UIMessagePart.Text(context)) else it }
    }
}
