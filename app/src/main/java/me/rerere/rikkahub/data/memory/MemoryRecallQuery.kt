package me.rerere.rikkahub.data.memory

/** A bounded hint for explicit references, not a general semantic follow-up classifier. */
internal fun buildMemoryRecallQuery(current: String, previousUser: String?): String {
    val text = current.trim()
    if (text.isBlank()) return ""
    val compact = text.replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
    val refersBack = compact in setOf(
        "这个呢", "那个呢", "它呢", "他呢", "她呢", "为什么", "为什么呢", "然后呢", "后来呢",
        "还有呢", "继续", "继续说", "接着说", "你看不到吗", "看不到吗", "你没看到吗", "你没收到吗",
    )
    val budget = MemoryTextBudget()
    val context = previousUser?.trim()?.takeIf { refersBack && it.isNotBlank() }
    if (context == null) return budget.prefix(text, 2000)
    // Reserve most of the query budget for the current request. Never include generated assistant text.
    return budget.prefix(text, 1500) + "\n" + budget.prefix(context, 499)
}
