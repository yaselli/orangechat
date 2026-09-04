/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraInfoInjectionTest {
    @Test
    fun `persisted extra info part is identifiable`() {
        assertTrue(extraInfoMessagePart("context").isExtraInfoInjectionPart())
    }

    @Test
    fun `ordinary user text is not treated as extra info`() {
        assertFalse(UIMessagePart.Text("hello").isExtraInfoInjectionPart())
    }
}
