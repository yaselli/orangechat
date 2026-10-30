package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.*
import org.junit.Test

class ConversationSizeInfoTest {
    @Test
    fun `long uncompacted conversation retains the existing warning`() {
        assertTrue(conversationSizeInfo(771, 330_000, 1_000, null).showWarning)
    }

    @Test
    fun `saving a summary makes earlier high usage stale without deleting messages`() {
        val before = conversationSizeInfo(771, 330_000, 1_000, null)
        val after = conversationSizeInfo(771, 330_000, 1_000, 2_000)
        assertTrue(before.showWarning)
        assertEquals(771, after.nodeCount)
        assertTrue(after.exceedNodeCountThreshold)
        assertEquals(0, after.lastAssistantInputTokens)
        assertFalse(after.exceedInputTokenThreshold)
        assertFalse(after.showWarning)
    }

    @Test
    fun `a fresh 60k reply after compression does not trigger the warning`() {
        val info = conversationSizeInfo(771, 60_000, 3_000, 2_000)
        assertEquals(60_000, info.lastAssistantInputTokens)
        assertFalse(info.showWarning)
    }

    @Test
    fun `a later genuinely large request can still trigger the warning`() {
        assertTrue(conversationSizeInfo(800, 330_000, 3_000, 2_000).showWarning)
    }

    @Test
    fun `recompression invalidates the previous post-compression usage again`() {
        assertTrue(conversationSizeInfo(800, 330_000, 3_000, 2_000).showWarning)
        assertFalse(conversationSizeInfo(800, 330_000, 3_000, 4_000).showWarning)
    }

    @Test
    fun `missing usage or unknown freshness is not evidence of an oversized request`() {
        assertFalse(conversationSizeInfo(771, null, 3_000, 2_000).showWarning)
        assertFalse(conversationSizeInfo(771, 330_000, null, 2_000).showWarning)
        assertFalse(conversationSizeInfo(771, 330_000, 2_000, 2_000).showWarning)
    }

    @Test
    fun `thresholds remain strict and a high input alone does not warn about local history`() {
        assertFalse(conversationSizeInfo(768, 330_000, 3_000, null).showWarning)
        assertFalse(conversationSizeInfo(771, 300_000, 3_000, null).showWarning)
        assertTrue(conversationSizeInfo(769, 300_001, 3_000, null).showWarning)
    }
}
