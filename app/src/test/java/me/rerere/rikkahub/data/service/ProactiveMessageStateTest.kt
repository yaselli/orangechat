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
    fun `explanation followed by bare stop is treated as control`() {
        val decision = parseProactiveDecision("这时候不该继续打扰。\nSTOP", false)
        assertFalse(decision.shouldSend)
        assertTrue(decision.stopUntilUserReturns)
        assertEquals("", decision.message)
    }
}
