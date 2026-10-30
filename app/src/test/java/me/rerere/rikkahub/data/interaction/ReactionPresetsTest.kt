package me.rerere.rikkahub.data.interaction

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class ReactionPresetsTest {
    @Test
    fun `AI tool exposes kaomoji and executes it with the original target and quote`() = runBlocking {
        val selected = "(づ｡◕‿‿◕｡)づ"
        var received = ""
        val tool = buildChatInteractionTools(
            Assistant(enableMessageReactions = true), onAvatar = { "" },
            onReaction = { target, emoji, add, quote -> received = "$target|$emoji|$add|$quote" },
        ).single { it.name == "message_reaction" }
        val schema = tool.parameters() as InputSchema.Obj
        val supported = schema.properties["emoji"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue(selected in supported)
        assertTrue(CHAT_REACTION_EMOJIS.all { it in supported })
        tool.execute(Json.parseToJsonElement(
            """{"emoji":"(づ｡◕‿‿◕｡)づ","message_id":"by_quote","quote":"你好呀"}""",
        ))
        assertEquals("by_quote|$selected|true|你好呀", received)
    }
}
