package me.rerere.rikkahub.data.interaction

import java.security.MessageDigest

fun reactionKey(conversationId: String, messageId: String, actor: String, emoji: String, quote: String = ""): String =
    MessageDigest.getInstance("SHA-256").digest(
        "$conversationId\u0000$messageId\u0000$actor\u0000$emoji\u0000$quote".toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it) }

fun interactionText(actor: String, target: String, action: String, suffix: String): String {
    require(action.isNotBlank() && action.length <= 20) { "互动动作需为 1–20 个字" }
    require(suffix.length <= 80) { "互动后缀最多 80 个字" }
    return "$actor${action.trim()}$target${suffix.trim()}"
}

