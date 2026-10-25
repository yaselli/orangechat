package me.rerere.rikkahub.data.ai.tools

import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.*
import org.junit.Test

class ToolSurfacePolicyTest {
    private val local = listOf(LocalToolOption.TimeInfo, LocalToolOption.RequestVoiceCall, LocalToolOption.Workflows)
    private val system = setOf(SystemToolOption.AppUsage, SystemToolOption.PostNotification, SystemToolOption.Battery)

    @Test
    fun `default workflow policy preserves all configured tools and memory`() {
        val policy = ToolSurfacePolicy()
        assertTrue(policy.includeMemoryTools)
        assertEquals(local, policy.localOptions(local))
        assertEquals(system, policy.systemOptions(system))
        assertFalse(policy.requireReadyWorkspace)
    }

    @Test
    fun `chat does not register memory a second time and keeps ordinary tool exposure`() {
        val policy = ToolSurfacePolicy.forChat(allowAppUsage = true, chatBlocked = false)
        assertFalse(policy.includeMemoryTools)
        assertTrue(policy.allowSharedMusic)
        assertEquals(local, policy.localOptions(local))
        assertEquals(system, policy.systemOptions(system))
        assertTrue(policy.requireReadyWorkspace)
    }

    @Test
    fun `proactive check-in restriction excludes only app usage`() {
        val policy = ToolSurfacePolicy.forChat(allowAppUsage = false, chatBlocked = false)
        assertEquals(system - SystemToolOption.AppUsage, policy.systemOptions(system))
        assertEquals(local, policy.localOptions(local))
        assertTrue(SystemToolOption.AppUsage in system)
    }

    @Test
    fun `blocked chat excludes voice call and shared music while retaining workflow authoring tools`() {
        val policy = ToolSurfacePolicy.forChat(allowAppUsage = true, chatBlocked = true)
        assertFalse(policy.allowSharedMusic)
        assertEquals(listOf(LocalToolOption.TimeInfo, LocalToolOption.Workflows), policy.localOptions(local))
        assertEquals(system, policy.systemOptions(system))
        assertTrue(LocalToolOption.RequestVoiceCall in local)
    }

    @Test
    fun `combined restrictions do not enable tools that were not configured`() {
        val policy = ToolSurfacePolicy.forChat(allowAppUsage = false, chatBlocked = true)
        assertEquals(emptyList<LocalToolOption>(), policy.localOptions(listOf(LocalToolOption.RequestVoiceCall)))
        assertEquals(emptySet<SystemToolOption>(), policy.systemOptions(setOf(SystemToolOption.AppUsage)))
        assertTrue(policy.localOptions(emptyList()).isEmpty())
        assertTrue(policy.systemOptions(emptySet()).isEmpty())
    }

    @Test
    fun `chat workspace tools require an existing ready workspace`() {
        val policy = ToolSurfacePolicy.forChat(allowAppUsage = true, chatBlocked = false)
        for (status in WorkspaceShellStatus.entries) {
            assertEquals(status == WorkspaceShellStatus.READY, policy.workspaceAllowed("workspace", status.name))
        }
        assertFalse(policy.workspaceAllowed("workspace", null))
        assertFalse(policy.workspaceAllowed(null, WorkspaceShellStatus.READY.name))
        assertFalse(policy.workspaceAllowed("", WorkspaceShellStatus.READY.name))
    }

    @Test
    fun `workflow keeps its prior workspace exposure while absent workspace stays absent`() {
        val policy = ToolSurfacePolicy()
        for (status in WorkspaceShellStatus.entries) assertTrue(policy.workspaceAllowed("workspace", status.name))
        assertTrue(policy.workspaceAllowed("workspace", null))
        assertFalse(policy.workspaceAllowed(null, null))
        assertFalse(policy.workspaceAllowed("  ", null))
    }
}
