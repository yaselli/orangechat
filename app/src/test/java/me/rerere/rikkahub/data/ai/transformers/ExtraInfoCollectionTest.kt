package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

class ExtraInfoCollectionTest {
    @Test fun failedSourceDoesNotPreventOtherContextAndDoesNotExposeContent() = runBlocking {
        val failed = collectExtraInfoItem(1_000) { error("private screen text") }
        val success = collectExtraInfoItem(1_000) { "current context" }
        assertNull(failed.text)
        assertEquals("failed:IllegalStateException", failed.status)
        assertEquals("current context", success.text)
        assertEquals("success", success.status)
    }

    @Test fun emptyAndTimeoutAreDistinguished() = runBlocking {
        assertEquals("empty", collectExtraInfoItem(1_000) { "  " }.status)
        assertEquals("timeout", collectExtraInfoItem(20) { awaitCancellation() }.status)
    }

    @Test fun cancellationIsNotTreatedAsAnOptionalFailure() = runBlocking {
        val cancellation = CancellationException("stop")
        try {
            collectExtraInfoItem(1_000) { throw cancellation }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            // Coroutine stack-trace recovery may copy the exception across suspension boundaries.
            assertEquals(cancellation.javaClass, actual.javaClass)
            assertEquals(cancellation.message, actual.message)
        }
    }

    @Test fun proactiveAppUsageConsentDoesNotDisableScreenOrChangeSavedOptions() {
        val base = Settings.dummy()
        val settings = base.copy(
            systemToolsSetting = base.systemToolsSetting.copy(
                extraInfoInjectionEnabled = true,
                currentScreenAppContextInjectionEnabled = true,
                recentAppUsageContextInjectionEnabled = true,
                screenTextContextInjectionEnabled = true,
            ),
            proactiveMessageSetting = base.proactiveMessageSetting.copy(allowProactiveAppUsage = false),
        )
        val effective = settings.forProactiveExtraInfo()
        assertFalse(effective.systemToolsSetting.currentScreenAppContextInjectionEnabled)
        assertFalse(effective.systemToolsSetting.recentAppUsageContextInjectionEnabled)
        assertTrue(effective.systemToolsSetting.screenTextContextInjectionEnabled)
        assertTrue(settings.systemToolsSetting.recentAppUsageContextInjectionEnabled)
        val authorized = settings.copy(proactiveMessageSetting = settings.proactiveMessageSetting.copy(
            allowProactiveAppUsage = true,
        ))
        assertSame(authorized, authorized.forProactiveExtraInfo())
    }
}
