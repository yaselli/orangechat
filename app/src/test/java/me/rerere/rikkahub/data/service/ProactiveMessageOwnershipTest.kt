/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveMessageOwnershipTest {
    @Test
    fun `assistant-ended history starts a fresh proactive assistant id`() {
        val user = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("我睡了")),
        )
        val existingAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("好，晚安")),
        )

        val streamMessages = beginProactiveAssistantTurn(
            messages = listOf(user, existingAssistant),
            modelId = null,
        )

        assertEquals(3, streamMessages.size)
        assertEquals(existingAssistant, streamMessages[1])
        assertEquals(MessageRole.ASSISTANT, streamMessages.last().role)
        assertNotEquals(existingAssistant.id, streamMessages.last().id)
        assertTrue(streamMessages.last().parts.isEmpty())
    }

    @Test
    fun `stop cleanup cannot claim or remove pre-existing assistant`() {
        val existingAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("好，晚安")),
        )
        val generatedAssistant = beginProactiveAssistantTurn(
            messages = listOf(existingAssistant),
            modelId = null,
        ).last()
        val protectedIds = setOf(existingAssistant.id)
        val runIds = linkedSetOf<kotlin.uuid.Uuid>()

        assertFalse(registerProactiveRunMessageId(existingAssistant.id, protectedIds, runIds))
        assertTrue(registerProactiveRunMessageId(generatedAssistant.id, protectedIds, runIds))

        val cleanupIds = ownedProactiveCleanupIds(
            requestedIds = runIds + existingAssistant.id,
            protectedMessageIds = protectedIds,
        )
        val remaining = listOf(existingAssistant, generatedAssistant)
            .filterNot { it.id in cleanupIds }

        assertEquals(setOf(generatedAssistant.id), cleanupIds)
        assertEquals(listOf(existingAssistant), remaining)
    }
}
