package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import kotlin.uuid.Uuid

/** Request controls belong to the conversation; persona, memory and appearance remain on the assistant. */
@Serializable
data class ConversationConfig(
    val chatModelId: Uuid? = null,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val enableWebSearch: Boolean = false,
    val builtInSearch: Boolean = false,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val contextMessageSize: Int = 0,
    val mcpServers: Set<Uuid> = emptySet(),
    val enabledSkills: Set<String> = emptySet(),
    val localTools: List<LocalToolOption> = emptyList(),
    val workspaceId: Uuid? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
) {
    fun withAssistant(value: Assistant) = copy(
        chatModelId = value.chatModelId, reasoningLevel = value.reasoningLevel,
        temperature = value.temperature, topP = value.topP, maxTokens = value.maxTokens,
        contextMessageSize = value.contextMessageSize, mcpServers = value.mcpServers,
        enabledSkills = value.enabledSkills, localTools = value.localTools, workspaceId = value.workspaceId,
        modeInjectionIds = value.modeInjectionIds, lorebookIds = value.lorebookIds,
    )
}

fun Assistant.withConversation(config: ConversationConfig?): Assistant = config?.let {
    copy(chatModelId = it.chatModelId, reasoningLevel = it.reasoningLevel,
        temperature = it.temperature, topP = it.topP, maxTokens = it.maxTokens,
        contextMessageSize = it.contextMessageSize, mcpServers = it.mcpServers,
        enabledSkills = it.enabledSkills, localTools = it.localTools, workspaceId = it.workspaceId,
        modeInjectionIds = it.modeInjectionIds, lorebookIds = it.lorebookIds)
} ?: this

fun Settings.getAssistantOf(conversation: Conversation): Assistant =
    (getAssistantById(conversation.assistantId) ?: getCurrentAssistant()).withConversation(conversation.config)

fun Settings.getChatModelOf(conversation: Conversation): Model? {
    val stored = getAssistantById(conversation.assistantId) ?: getCurrentAssistant()
    val selected = conversation.config?.chatModelId?.let(::findModelById)
        ?: findModelById(stored.chatModelId ?: chatModelId) ?: findModelById(chatModelId) ?: return null
    val config = conversation.config ?: return selected
    if (selected.id != config.chatModelId) return selected
    return selected.copy(tools = if (config.builtInSearch) selected.tools + BuiltInTools.Search
        else selected.tools - BuiltInTools.Search)
}

/** Resolve all consumers from one settings snapshot, without writing these overlays to DataStore. */
fun Settings.withConversationConfig(conversation: Conversation): Settings {
    val model = getChatModelOf(conversation)
    val resolved = getAssistantOf(conversation)
    val assistant = if (model == null) resolved else resolved.copy(chatModelId = model.id)
    return copy(
        assistantId = assistant.id,
        assistants = assistants.map { if (it.id == assistant.id) assistant else it },
        enableWebSearch = conversation.config?.enableWebSearch ?: enableWebSearch,
        providers = providers.map { provider ->
            if (model == null || provider.models.none { it.id == model.id }) provider else provider.editModel(model)
        },
    )
}

/** Called when starting a new turn, or explicitly synchronizing role defaults. */
fun Conversation.bindConfig(settings: Settings): Conversation {
    if (config != null) {
        val model = settings.getChatModelOf(this)
        return if (model == null || model.id == config.chatModelId) this else copy(
            config = config.copy(chatModelId = model.id, builtInSearch = BuiltInTools.Search in model.tools),
        )
    }
    val assistant = settings.getAssistantById(assistantId) ?: settings.getCurrentAssistant()
    val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
    return copy(config = ConversationConfig(
        enableWebSearch = settings.enableWebSearch,
        builtInSearch = model?.tools?.contains(BuiltInTools.Search) == true,
    ).withAssistant(assistant).copy(chatModelId = model?.id))
}
