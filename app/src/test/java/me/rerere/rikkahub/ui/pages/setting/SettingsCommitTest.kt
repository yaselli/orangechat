package me.rerere.rikkahub.ui.pages.setting

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SettingsCommitTest {
    @Test fun triggerWaitsForPersistenceAndReceivesCommittedValue() = runBlocking {
        val diskReady = CompletableDeferred<Unit>()
        var storedEnabled = false
        var triggerCount = 0
        val update = launch(start = CoroutineStart.UNDISPATCHED) {
            persistThenNotify(
                persist = { diskReady.await(); storedEnabled = true },
                readCommitted = { storedEnabled },
                onCommitted = { enabled -> assertTrue(enabled); triggerCount++ },
            )
        }
        assertEquals(0, triggerCount)
        assertFalse(storedEnabled)
        diskReady.complete(Unit)
        update.join()
        assertEquals(1, triggerCount)
    }

    @Test fun failedPersistenceCannotStartOrCancelScheduling() = runBlocking {
        val failure = IllegalStateException("disk write failed")
        var effectCalled = false
        try {
            persistThenNotify(
                persist = { throw failure },
                readCommitted = { true },
                onCommitted = { effectCalled = true },
            )
            fail("Expected persistence failure")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertFalse(effectCalled)
    }
}
