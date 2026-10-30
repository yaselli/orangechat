package me.rerere.rikkahub.data.message

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class MessageQuoteTest {
    private val quote = MessageQuote("source-id", "沈渡", "只引用这一句～\n下一行 > 😊")

    @Test
    fun quoteSurvivesBackupWithoutExposingItsBodyAsOrdinaryHistoryText() {
        val part = quote.toPart()
        assertEquals("", part.text)
        val stored = UIMessage.user("接这一句").copy(parts = listOf(part, UIMessagePart.Text("接这一句")))
        val restored =
            Json.decodeFromString<UIMessage>(Json.encodeToString(UIMessage.serializer(), stored))
        assertEquals(quote, restored.parts.first().messageQuote())
        assertNull(UIMessagePart.Text("以前的普通消息").messageQuote())
    }

    @Test
    fun onlyTheCurrentUserTurnExpandsItsOwnQuoteAndStorageIsUnchanged() {
        val old =
            UIMessage.user("上一轮").copy(parts = listOf(quote.toPart(), UIMessagePart.Text("上一轮")))
        val currentQuote = quote.copy(text = "新引用")
        val current =
            UIMessage.user("现在的回复")
                .copy(parts = listOf(currentQuote.toPart(), UIMessagePart.Text("现在的回复")))
        val history = listOf(old, UIMessage.assistant("已回复"), current)
        val request = history.withCurrentTurnQuote(history.quoteTurnUserId())
        assertFalse(
            request.first().parts.filterIsInstance<UIMessagePart.Text>().any {
                quote.requestPart().text in it.text
            }
        )
        assertTrue(
            request.last().parts.filterIsInstance<UIMessagePart.Text>().any { "新引用" in it.text }
        )
        assertEquals(quote, history.first().parts.first().messageQuote())
        assertEquals(currentQuote, history.last().parts.first().messageQuote())
    }

    @Test
    fun manyAssistantBubblesAndLaterUserTurnsNeverReinjectAnOldQuote() {
        val quoted =
            UIMessage.user("我的回复").copy(parts = listOf(quote.toPart(), UIMessagePart.Text("我的回复")))
        val original = listOf(quoted)
        val anchor = original.quoteTurnUserId()
        val sameGeneration = original + UIMessage.assistant("第一句\n第二句\n第三句")
        // The same captured turn remains available through all tool-loop requests.
        assertTrue(
            sameGeneration.withCurrentTurnQuote(anchor).first().parts.any {
                it is UIMessagePart.Text && quote.requestPart().text in it.text
            }
        )
        // A separate proactive continuation after a completed answer has no active quoted turn.
        assertNull(sameGeneration.quoteTurnUserId())
        assertFalse(
            sameGeneration
                .withCurrentTurnQuote(null)
                .flatMap { it.parts }
                .any { it is UIMessagePart.Text && quote.requestPart().text in it.text }
        )
        val later =
            sameGeneration +
                UIMessage.user("换个话题") +
                UIMessage.assistant("好呀") +
                UIMessage.user("继续")
        assertFalse(
            later
                .withCurrentTurnQuote(later.quoteTurnUserId())
                .flatMap { it.parts }
                .any {
                    it is UIMessagePart.Text && quote.requestPart().text in it.text
                }
        )
        assertEquals(quote, later.first().parts.first().messageQuote())
    }

    @Test
    fun approvalResumesOnlyTheUnfinishedTurnAndBranchRequestsRemainIndependent() {
        val user =
            UIMessage.user("回复").copy(parts = listOf(quote.toPart(), UIMessagePart.Text("回复")))
        val waiting =
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts =
                    listOf(
                        UIMessagePart.Tool(
                            "tool-id",
                            "search",
                            "{}",
                            approvalState = ToolApprovalState.Pending,
                        )
                    ),
            )
        assertEquals(user.id, listOf(user, waiting).quoteTurnUserId())
        val otherBranch = listOf(UIMessage.user("没有引用的另一分支"))
        assertFalse(
            otherBranch
                .withCurrentTurnQuote(otherBranch.quoteTurnUserId())
                .flatMap { it.parts }
                .any {
                    it is UIMessagePart.Text && quote.requestPart().text in it.text
                }
        )
    }
}
