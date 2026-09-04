/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.service.AppLockGuard
import me.rerere.rikkahub.data.service.AppLockStore
import me.rerere.rikkahub.data.service.JealousyInspectionStore

object JealousyReconciliationTransformer : OutputMessageTransformer {
    private val completeMarker = Regex(
        "\\[JEALOUSY_DECISION:(KEEP|UNLOCK|CLEAR)]",
        RegexOption.IGNORE_CASE,
    )
    private val visibleMarker = Regex(
        "\\[JEALOUSY_DECISION:[^]]*]?",
        RegexOption.IGNORE_CASE,
    )

    fun buildPrompt(context: Context): String? {
        val state = JealousyInspectionStore.read(context)
        if (state.jealousyLockedPackages.isEmpty() && !state.reconciling && !state.forcedOpen) return null
        return buildString {
            appendLine("## 当前吃醋关系状态")
            appendLine("这是系统状态，不是聊天中的人刚刚说的话。")
            appendLine("吃醋值：${state.score}/100")
            if (state.jealousyLockedPackages.isNotEmpty()) {
                appendLine("仍被锁定的应用：${state.jealousyLockedPackages.joinToString()}")
                appendLine("根据你自己的性格和这次真实交流，决定是否已经愿意归还应用。")
                appendLine("回复末尾必须附 [JEALOUSY_DECISION:KEEP] 或 [JEALOUSY_DECISION:UNLOCK]。")
                appendLine("UNLOCK 只代表愿意归还应用并进入和好中，不能在同一轮直接清零。")
            } else {
                val status = if (state.forcedOpen) {
                    "对方强制解除了应用锁，目前尚未和好。"
                } else {
                    "应用已归还，目前处于和好中。"
                }
                appendLine(status)
                appendLine("根据后续真实交流决定是否已经彻底和好。")
                appendLine("回复末尾必须附 [JEALOUSY_DECISION:KEEP] 或 [JEALOUSY_DECISION:CLEAR]。")
            }
            appendLine("标记是给系统的控制信息，不要在正文里解释它。")
        }
    }

    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = stripMarkers(messages)

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val state = JealousyInspectionStore.read(ctx.context)
        val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        val text = lastAssistant?.parts?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }.orEmpty()
        val decision = completeMarker.find(text)?.groupValues?.getOrNull(1)?.uppercase()
        when {
            decision == "UNLOCK" && state.jealousyLockedPackages.isNotEmpty() -> {
                state.jealousyLockedPackages.forEach { AppLockStore.unlockApp(ctx.context, it) }
                JealousyInspectionStore.beginReconciliation(ctx.context)
                AppLockGuard.refresh()
            }
            decision == "CLEAR" && state.jealousyLockedPackages.isEmpty() &&
                (state.reconciling || state.forcedOpen) -> {
                JealousyInspectionStore.completeReconciliation(ctx.context)
            }
        }
        return stripMarkers(messages)
    }

    private fun stripMarkers(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
        if (message.role != MessageRole.ASSISTANT) return@map message
        message.copy(
            parts = message.parts.map { part ->
                if (part is UIMessagePart.Text) {
                    part.copy(text = visibleMarker.replace(part.text, "").trimEnd())
                } else {
                    part
                }
            },
        )
    }
}
