/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import org.junit.Assert.assertEquals
import org.junit.Test

class JealousyScoreCalculatorTest {
    @Test
    fun `one point is added per complete managed app minute`() {
        assertEquals(
            14,
            JealousyScoreCalculator.calculate(
                totalUsageMillis = 14 * 60_000L + 59_999L,
                continuousBonusCount = 0,
            ),
        )
    }

    @Test
    fun `continuous use bonus adds ten points`() {
        assertEquals(
            25,
            JealousyScoreCalculator.calculate(
                totalUsageMillis = 15 * 60_000L,
                continuousBonusCount = 1,
            ),
        )
    }

    @Test
    fun `fragmented durations still accumulate into whole minutes`() {
        assertEquals(
            1,
            JealousyScoreCalculator.calculate(
                totalUsageMillis = 75_000L,
                continuousBonusCount = 0,
            ),
        )
    }
}
