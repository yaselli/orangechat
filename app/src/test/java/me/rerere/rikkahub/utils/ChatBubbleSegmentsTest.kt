package me.rerere.rikkahub.utils

import me.rerere.rikkahub.data.datastore.ChatDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatBubbleSegmentsTest {
    @Test
    fun everydaySplitsBothRolesWithoutChangingExistingAssistantFlags() {
        assertEquals(
            listOf("第一句。", "第二句。"),
            "第一句。\n\n第二句。".chatBubbleSegments(ChatDisplayMode.EVERYDAY, false),
        )
    }

    @Test
    fun standardKeepsItsExistingSplitBehavior() {
        val text = "第一句。\n第二句。"
        assertEquals(listOf(text), text.chatBubbleSegments(ChatDisplayMode.STANDARD, false))
        assertEquals(
            listOf("第一句。", "第二句。"),
            text.chatBubbleSegments(ChatDisplayMode.STANDARD, true),
        )
    }

    @Test
    fun codeAndTablesRemainWholeWhileSurroundingLinesSplit() {
        val code = "```kotlin\nval a = 1\nprintln(a)\n```"
        val table = "| A | B |\n|---|---|\n| 1 | 2 |"
        assertEquals(
            listOf("开头", code, table, "结尾"),
            "开头\n$code\n$table\n结尾".chatBubbleSegments(ChatDisplayMode.EVERYDAY, false),
        )
    }
}
