/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveMessageStateTest {
    @Test
    fun `plain text is a send decision`() {
        val decision = parseProactiveDecision("跑哪去了？", false)
        assertTrue(decision.shouldSend)
        assertEquals("跑哪去了？", decision.message)
    }

    @Test
    fun `jump is removed and preserved as action`() {
        val decision = parseProactiveDecision("回来看看我\n[jump]", false)
        assertTrue(decision.shouldSend)
        assertTrue(decision.shouldJump)
        assertEquals("回来看看我", decision.message)
    }

    @Test
    fun `wait controls scheduling without sending`() {
        val decision = parseProactiveDecision("[WAIT:480]", false)
        assertFalse(decision.shouldSend)
        assertEquals(480, decision.waitMinutes)
    }

    @Test
    fun `stop waits for a new real user message`() {
        val decision = parseProactiveDecision("[STOP]", false)
        assertFalse(decision.shouldSend)
        assertTrue(decision.stopUntilUserReturns)
    }

    @Test
    fun `bare stop with punctuation never leaks into chat`() {
        val decision = parseProactiveDecision("STOP。", false)
        assertFalse(decision.shouldSend)
        assertTrue(decision.stopUntilUserReturns)
        assertEquals("", decision.message)
    }

    @Test
    fun `bare pass never leaks into chat`() {
        val decision = parseProactiveDecision(" pass ", false)
        assertFalse(decision.shouldSend)
        assertEquals("", decision.message)
    }

    @Test
    fun `bare wait accepts spaces and full width colon`() {
        val decision = parseProactiveDecision("WAIT ： 90。", false)
        assertFalse(decision.shouldSend)
        assertEquals(90, decision.waitMinutes)
        assertEquals("", decision.message)
    }

    @Test
    fun `normal sentence containing stop is not swallowed`() {
        val decision = parseProactiveDecision("别说 stop 这种词啦", false)
        assertTrue(decision.shouldSend)
        assertEquals("别说 stop 这种词啦", decision.message)
    }

    @Test
    fun `normal sentence mentioning bracket pass is not swallowed`() {
        val decision = parseProactiveDecision("别再给我输出 [PASS] 了。", false)
        assertTrue(decision.shouldSend)
        assertEquals("别再给我输出 [PASS] 了。", decision.message)
    }

    @Test
    fun `explanation followed by bare stop is treated as control`() {
        val decision = parseProactiveDecision("这时候不该继续打扰。\nSTOP", false)
        assertFalse(decision.shouldSend)
        assertTrue(decision.stopUntilUserReturns)
        assertEquals("", decision.message)
    }

    @Test
    fun `pass in reasoning never overrides visible final text`() {
        val decision = parseProactiveDecision(
            rawText = "玩完早点回来，我等你。",
            reasoningText = "她说去玩了，我不硬催。选 PASS，之后再看看。",
            jumpDetectedDuringStreaming = false,
        )
        assertTrue(decision.shouldSend)
        assertEquals("玩完早点回来，我等你。", decision.message)
    }

    @Test
    fun `negative pass phrase in reasoning does not swallow reply`() {
        val decision = parseProactiveDecision(
            rawText = "还是提醒她早点休息。",
            reasoningText = "这次不选 PASS，发一句简短提醒。",
            jumpDetectedDuringStreaming = false,
        )
        assertTrue(decision.shouldSend)
    }

    @Test
    fun `indirect negative pass phrase in reasoning does not swallow reply`() {
        val decision = parseProactiveDecision(
            rawText = "我还是来问问你在干嘛。",
            reasoningText = "我不打算选择 PASS，还是发一句吧。",
            jumpDetectedDuringStreaming = false,
        )
        assertTrue(decision.shouldSend)
        assertEquals("我还是来问问你在干嘛。", decision.message)
    }

    @Test
    fun `hypothetical pass phrase in reasoning does not swallow reply`() {
        val decision = parseProactiveDecision(
            rawText = "我还是想叫她一声。",
            reasoningText = "如果选择 PASS 就会错过这次关心，应该发消息。",
            jumpDetectedDuringStreaming = false,
        )
        assertTrue(decision.shouldSend)
    }

    @Test
    fun `explicit final pass clause in reasoning is ignored`() {
        listOf("决定：PASS。", "最终 PASS。", "所以我选择 [PASS]，之后再看看。").forEach { reasoning ->
            val decision = parseProactiveDecision(
                rawText = "这句正文不应该发出去。",
                reasoningText = reasoning,
                jumpDetectedDuringStreaming = false,
            )
            assertTrue(reasoning, decision.shouldSend)
            assertEquals(reasoning, "这句正文不应该发出去。", decision.message)
        }
    }

    @Test
    fun `changing mind after pass in reasoning sends final text`() {
        val decision = parseProactiveDecision(
            rawText = "我还是问问你，在忙吗？",
            reasoningText = "我要不要 PASS 呢？算了，不 PASS，还是问问她吧。",
            jumpDetectedDuringStreaming = false,
        )
        assertTrue(decision.shouldSend)
        assertEquals("我还是问问你，在忙吗？", decision.message)
    }
}
