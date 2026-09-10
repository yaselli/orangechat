package me.rerere.rikkahub.ui.pages.setting

/** External effects must follow a successful durable write, not an optimistic UI update. */
internal suspend fun <T> persistThenNotify(
    persist: suspend () -> Unit,
    readCommitted: suspend () -> T,
    onCommitted: ((T) -> Unit)?,
) {
    persist()
    if (onCommitted != null) onCommitted(readCommitted())
}
