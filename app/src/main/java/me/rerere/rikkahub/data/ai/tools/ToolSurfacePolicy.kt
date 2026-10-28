package me.rerere.rikkahub.data.ai.tools

import me.rerere.workspace.WorkspaceShellStatus

/** Caller-specific exposure rules; execution and approval stay with the existing callers. */
data class ToolSurfacePolicy(
    val includeMemoryTools: Boolean = true,
    val allowAppUsage: Boolean = true,
    val allowVoiceCall: Boolean = true,
    val allowSharedMusic: Boolean = true,
    val allowProactiveWakeup: Boolean = true,
    val requireReadyWorkspace: Boolean = false,
) {
    internal fun localOptions(options: List<LocalToolOption>): List<LocalToolOption> =
        options.filterNot {
            !allowVoiceCall && it == LocalToolOption.RequestVoiceCall
        }

    internal fun systemOptions(options: Set<SystemToolOption>): Set<SystemToolOption> =
        options.filterNot { !allowAppUsage && it == SystemToolOption.AppUsage }.toSet()

    internal fun workspaceAllowed(workspaceId: String?, shellStatus: String?): Boolean =
        !workspaceId.isNullOrBlank() &&
            (!requireReadyWorkspace || shellStatus == WorkspaceShellStatus.READY.name)

    companion object {
        fun forChat(allowAppUsage: Boolean, chatBlocked: Boolean) =
            ToolSurfacePolicy(
                // GenerationHandler already adds memory tools to ordinary chat model turns.
                includeMemoryTools = false,
                allowAppUsage = allowAppUsage,
                allowVoiceCall = !chatBlocked,
                allowSharedMusic = !chatBlocked,
                allowProactiveWakeup = !chatBlocked,
                requireReadyWorkspace = true,
            )
    }
}
