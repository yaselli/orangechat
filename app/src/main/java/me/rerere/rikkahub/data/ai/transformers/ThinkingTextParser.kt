package me.rerere.rikkahub.data.ai.transformers

internal data class ThinkingTextSegment(
    val text: String,
    val reasoning: Boolean = false,
    val closed: Boolean = true,
)

private val THINKING_TOKEN = Regex("`+|~{3,}|<(/?)(think|thinking)\\s*>", RegexOption.IGNORE_CASE)

/** Split explicit relay tags, preserving order and every block. Untagged prose is never guessed. */
internal fun splitThinkingText(text: String): List<ThinkingTextSegment> {
    val result = mutableListOf<ThinkingTextSegment>()
    var position = 0
    var thinkingTag: String? = null
    var codeMarker: Char? = null
    var codeWidth = 0

    for (match in THINKING_TOKEN.findAll(text)) {
        val marker = match.value.first()
        if (marker == '`' || marker == '~') {
            if (codeMarker == null) {
                codeMarker = marker
                codeWidth = match.value.length
            } else if (
                codeMarker == marker &&
                    (match.value.length == codeWidth ||
                        codeWidth >= 3 && match.value.length > codeWidth)
            ) {
                codeMarker = null
            }
            continue
        }
        if (codeMarker != null) continue
        val name = match.groupValues[2].lowercase()
        val closing = match.groupValues[1] == "/"
        if (thinkingTag == null && !closing) {
            if (position < match.range.first)
                result += ThinkingTextSegment(text.substring(position, match.range.first))
            thinkingTag = name
            position = match.range.last + 1
        } else if (thinkingTag == name && closing) {
            result +=
                ThinkingTextSegment(text.substring(position, match.range.first), reasoning = true)
            thinkingTag = null
            position = match.range.last + 1
        }
    }

    if (thinkingTag != null) {
        result += ThinkingTextSegment(text.substring(position), reasoning = true, closed = false)
    } else if (position < text.length || result.isEmpty()) {
        result += ThinkingTextSegment(text.substring(position))
    }
    return result
}
