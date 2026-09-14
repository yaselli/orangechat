package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.datastore.Settings

/** Preserve the separate proactive app-usage authorization without changing saved settings. */
internal fun Settings.forProactiveExtraInfo(): Settings = if (proactiveMessageSetting.allowProactiveAppUsage) {
    this
} else {
    copy(systemToolsSetting = systemToolsSetting.copy(
        currentScreenAppContextInjectionEnabled = false,
        recentAppUsageContextInjectionEnabled = false,
    ))
}

internal data class ExtraInfoCollectionResult(val status: String, val text: String? = null)

/** Optional sources may fail independently; cancellation must still stop the parent request. */
internal suspend fun collectExtraInfoItem(
    timeoutMillis: Long,
    collect: suspend () -> String,
): ExtraInfoCollectionResult = withTimeoutOrNull(timeoutMillis) {
    try {
        val text = collect().takeIf { it.isNotBlank() }
        ExtraInfoCollectionResult(if (text == null) "empty" else "success", text)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Never include exception messages: providers may embed screen text, URLs or credentials.
        ExtraInfoCollectionResult("failed:${error.javaClass.simpleName}")
    }
} ?: ExtraInfoCollectionResult("timeout")
