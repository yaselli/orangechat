package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationConfigTest {
    private val first = Model(modelId = "first", tools = setOf(BuiltInTools.Search))
    private val second = Model(modelId = "second")
    private val owner = Assistant(chatModelId = first.id, temperature = 0.4f,
        mcpServers = setOf(Uuid.random()), enabledSkills = setOf("skill"))
    private val settings = Settings.dummy().copy(assistantId = owner.id, assistants = listOf(owner),
        chatModelId = first.id, providers = listOf(ProviderSetting.OpenAI(models = listOf(first, second))))
    private fun chat() = Conversation(Uuid.random(), owner.id, messageNodes = emptyList())

    @Test
    fun frozenRequestFieldsRemainIndependentWhilePersonaAndMemoryStayLive() {
        val bound = chat().bindConfig(settings)
        val changed = settings.copy(assistants = listOf(owner.copy(chatModelId = second.id, temperature = 1.5f,
            systemPrompt = "new persona", enableMemory = true, mcpServers = emptySet())))
        val resolved = changed.getAssistantOf(bound)
        assertEquals(first.id, resolved.chatModelId)
        assertEquals(0.4f, resolved.temperature)
        assertEquals(owner.mcpServers, resolved.mcpServers)
        assertEquals("new persona", resolved.systemPrompt)
        assertTrue(resolved.enableMemory)
        assertEquals(second.id, changed.getChatModelOf(chat())!!.id)
    }

    @Test
    fun explicitEmptyToolSelectionsAndSearchAreNeverReplacedByRoleDefaults() {
        val bound = chat().copy(config = ConversationConfig(chatModelId = first.id, builtInSearch = false))
        val view = settings.withConversationConfig(bound)
        assertTrue(view.getAssistantOf(bound).mcpServers.isEmpty())
        assertTrue(view.getAssistantOf(bound).enabledSkills.isEmpty())
        assertTrue(view.getAssistantOf(bound).localTools.isEmpty())
        assertFalse(BuiltInTools.Search in view.findModelById(first.id)!!.tools)
        assertTrue(BuiltInTools.Search in settings.findModelById(first.id)!!.tools)
        assertEquals(owner, settings.assistants.single())
    }

    @Test
    fun deletedModelFallsBackAndRebindsWithoutResettingOtherConversationChoices() {
        val bound = chat().bindConfig(settings)
        val changed = settings.copy(assistants = listOf(owner.copy(chatModelId = second.id)),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(second))))
        val rebound = bound.bindConfig(changed)
        assertEquals(second.id, rebound.config!!.chatModelId)
        assertEquals(bound.config!!.mcpServers, rebound.config!!.mcpServers)
        assertEquals(bound.config!!.temperature, rebound.config!!.temperature)
        assertFalse(rebound.config!!.builtInSearch)
    }

    @Test
    fun configRoundTripPreservesEmptyOverridesAndOldConversationDefaultsToUnbound() {
        val value = chat().bindConfig(settings).copy(config = ConversationConfig(chatModelId = second.id))
        assertEquals(value.config, JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(value)).config)
        assertNull(JsonInstant.decodeFromString<Conversation>(
            """{"id":"${Uuid.random()}","assistantId":"${owner.id}","messageNodes":[]}""").config)
    }
}
