package me.rerere.rikkahub.utils

import me.rerere.rikkahub.data.datastore.ChatDisplayMode

/** Split the display copy only; the original message and its model context remain untouched. */
internal fun String.chatBubbleSegments(mode: ChatDisplayMode, splitByLine: Boolean): List<String> =
    if (mode == ChatDisplayMode.EVERYDAY || splitByLine) splitIntoBubbleSegments() else listOf(this)
