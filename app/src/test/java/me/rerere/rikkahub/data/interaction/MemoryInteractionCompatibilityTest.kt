package me.rerere.rikkahub.data.interaction

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.memory.MemoryRecallState
import me.rerere.rikkahub.data.memory.MemoryRecallTurn
import me.rerere.rikkahub.data.memory.SavedMemoryExcerpt
import me.rerere.rikkahub.data.memory.memoryHash
import me.rerere.rikkahub.data.memory.memoryRecallTrace
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class MemoryInteractionCompatibilityTest {
    @Test
    fun `stable avatar annotations remain readable and coexist with request only recall`() {
        val source = UIMessage.user("你拍了拍AI").copy(
            annotations = listOf(UIMessageAnnotation.AvatarInteraction("user", "assistant")),
        )
        val restored = JsonInstant.decodeFromString<UIMessage>(JsonInstant.encodeToString(source))
        assertEquals(source, restored)
        val fact = MemoryEntity(id = 7, assistantId = "owner", content = "喜欢吃辣")
        val turn = MemoryRecallTurn("owner", source.id.toString(), listOf(
            SavedMemoryExcerpt(fact.id, fact.revision, memoryHash(fact.content), fact.content),
        ))
        val request = turn.apply(listOf(restored.forInteractionRequest()), mapOf(fact.id to fact)).single()
        assertEquals(source.annotations, request.annotations)
        assertTrue(request.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("App avatar event") })
        assertTrue(request.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("喜欢吃辣") })
        assertEquals("你拍了拍AI", (source.parts.single() as UIMessagePart.Text).text)
        assertEquals(MemoryRecallState.USED, turn.annotate(UIMessage.assistant("回答")).memoryRecallTrace()?.state)
    }
}
