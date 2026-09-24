package me.rerere.asr.providers

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ASRHttpTest {
    @Test
    fun `cancel aborts request and late failure cannot deliver result`() = runBlocking {
        val call = WaitingCall()
        var delivered = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            call.readAsrResponse()
            delivered = true
        }
        assertTrue(call.isExecuted())
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
        call.callback!!.onFailure(call, IOException("late cancellation callback"))
        assertFalse(delivered)
    }

    private class WaitingCall : Call {
        var callback: Callback? = null
        private var cancelled = false
        override fun request(): Request = Request.Builder().url("https://example.invalid").build()
        override fun execute(): Response = error("Only asynchronous execution is allowed")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted(): Boolean = callback != null
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = WaitingCall()
    }
}
