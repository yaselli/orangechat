/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

private const val TAG = "RikkaAccService"
private const val GESTURE_TIMEOUT_MS = 5_000L
private const val LOG_RING_SIZE = 50

data class ActionLogEntry(
    val type: String,
    val paramsSummary: String,
    val success: Boolean,
    val timestampMs: Long,
)

class RikkaAccessibilityService : AccessibilityService() {

    private val gestureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RikkaAcc-Gesture").apply { isDaemon = true }
    }
    private val gestureHandlerThread = HandlerThread("RikkaAcc-Callback").apply { start() }
    private val gestureHandler = Handler(gestureHandlerThread.looper)
    // Service 构造时 baseContext 尚未 attach，不能通过 ContextWrapper.mainLooper 取主线程。
    private val mainHandler = Handler(Looper.getMainLooper())
    private var jealousyOverlay: View? = null
    private var jealousyOverlayPackage: String? = null

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private val _lastActions = MutableStateFlow<List<ActionLogEntry>>(emptyList())
    val lastActions = _lastActions.asStateFlow()

    // Serialise overlapping gesture-dispatch callers. Each caller takes a ticket; the
    // shared current-counter advances when a caller's `finally` runs. Both fields are
    // INSTANCE-scoped (was a companion-static AtomicInteger pair previously) so a
    // service-process restart resets them — without this, an aborted caller leaving its
    // ticket unconsumed would deadlock every subsequent caller in the next bind cycle.
    private val serializeGate = AtomicInteger(0)
    private val gateCurrent = AtomicInteger(0)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _running.value = true
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        _running.value = false
        _lastActions.value = emptyList()
        Log.i(TAG, "AccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeJealousyOverlayNow()
        instance = null
        _running.value = false
        gestureHandlerThread.quitSafely()
        gestureExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 12 — feed foreground-app transitions to the workflow trigger dispatcher.
        // We only care about TYPE_WINDOW_STATE_CHANGED and only when the package name is
        // present. The dispatcher itself de-dupes (skips no-op transitions) and dispatches
        // off-thread, so this stays fast on the AccessibilityService dispatcher.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank()) {
                me.rerere.rikkahub.workflow.trigger.AppForegroundDispatcher.onForegroundChange(pkg)
            }
        }
    }

    override fun onInterrupt() {
        // Required override; no-op.
    }

    fun showJealousyLockOverlay(packageName: String) {
        mainHandler.post {
            if (jealousyOverlay != null && jealousyOverlayPackage == packageName) return@post
            removeJealousyOverlayNow()
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val appName = runCatching {
                val info = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)
            val message = me.rerere.rikkahub.data.service.AppLockStore
                .getLockMessage(this, packageName)
                .orEmpty()
            val root = FrameLayout(this).apply {
                setBackgroundColor(Color.argb(238, 255, 248, 245))
                isClickable = true
                isFocusable = true
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(30), dp(28), dp(24))
                background = GradientDrawable().apply {
                    cornerRadius = dp(28).toFloat()
                    setColor(Color.argb(245, 255, 255, 255))
                    setStroke(dp(1), Color.argb(210, 255, 255, 255))
                }
            }
            card.addView(TextView(this).apply {
                text = "🔒"
                textSize = 42f
                gravity = Gravity.CENTER
            })
            card.addView(TextView(this).apply {
                text = "$appName 暂时被收走了"
                textSize = 22f
                setTextColor(Color.rgb(35, 31, 30))
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(8))
            })
            if (message.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = message
                    textSize = 15f
                    setTextColor(Color.rgb(100, 82, 80))
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(18))
                })
            }
            card.addView(Button(this).apply {
                text = "回来找 TA"
                setOnClickListener {
                    hideJealousyLockOverlay()
                    startActivity(
                        Intent(
                            this@RikkaAccessibilityService,
                            me.rerere.rikkahub.RouteActivity::class.java,
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        },
                    )
                }
            })
            card.addView(Button(this).apply {
                text = "先离开"
                setOnClickListener {
                    hideJealousyLockOverlay()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            })
            root.addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.CENTER
                    marginStart = dp(24)
                    marginEnd = dp(24)
                },
            )
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT,
            )
            runCatching { windowManager.addView(root, params) }
                .onSuccess {
                    jealousyOverlay = root
                    jealousyOverlayPackage = packageName
                }
                .onFailure { Log.e(TAG, "Failed to show jealousy lock overlay", it) }
        }
    }

    fun hideJealousyLockOverlay() {
        mainHandler.post { removeJealousyOverlayNow() }
    }

    fun isJealousyLockOverlayVisible(): Boolean = jealousyOverlay != null

    private fun removeJealousyOverlayNow() {
        val view = jealousyOverlay ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(view) }
        jealousyOverlay = null
        jealousyOverlayPackage = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun appendLog(entry: ActionLogEntry) {
        val current = _lastActions.value
        val next = (current + entry).takeLast(LOG_RING_SIZE)
        _lastActions.value = next
    }

    /**
     * Dispatches a gesture and suspends until it completes / cancels / times out (5s).
     * Serializes through a single-thread executor so concurrent calls queue behind each other
     * (the OS rejects overlapping dispatchGesture calls).
     */
    suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean {
        val gate = serializeGate.incrementAndGet()
        try {
            // Wait for our turn in the queue (serializes overlapping callers). The
            // ensureActive() inside the spin lets `stopGeneration` actually break us out
            // of a wait — without it, a caller stuck waiting for a never-arriving prior
            // gate would ignore cancellation and burn power until the service died.
            while (gateCurrent.get() != gate - 1) {
                currentCoroutineContext().ensureActive()
                kotlinx.coroutines.delay(10)
            }
            val ok = withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val callback = object : GestureResultCallback() {
                        override fun onCompleted(d: GestureDescription) {
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onCancelled(d: GestureDescription) {
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                    val dispatched = dispatchGesture(gesture, callback, gestureHandler)
                    if (!dispatched && cont.isActive) cont.resume(false)
                }
            } ?: false
            return ok
        } finally {
            gateCurrent.set(gate)
        }
    }

    fun buildTapPath(x: Float, y: Float): Path = Path().apply { moveTo(x, y) }

    fun buildSwipePath(sx: Float, sy: Float, ex: Float, ey: Float): Path = Path().apply {
        moveTo(sx, sy)
        lineTo(ex, ey)
    }

    /**
     * Walk the active window's node tree depth-first. `filter` decides whether to emit a node;
     * traversal stops once `cap` nodes have been emitted. Returns (emitted, totalSeen, truncated).
     */
    fun traverseTree(
        root: AccessibilityNodeInfo,
        filter: (AccessibilityNodeInfo, depth: Int) -> Boolean,
        cap: Int,
        emit: (AccessibilityNodeInfo, depth: Int, traversalIndex: Int) -> Unit,
    ): Triple<Int, Int, Boolean> {
        var emitted = 0
        var seen = 0
        var truncated = false
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (truncated) return
            seen++
            if (filter(n, depth)) {
                emit(n, depth, seen)
                emitted++
                if (emitted >= cap) {
                    truncated = true
                    return
                }
            }
            for (i in 0 until n.childCount) {
                if (truncated) return
                val child = n.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return Triple(emitted, seen, truncated)
    }

    /**
     * Walks up the parent chain looking for a clickable node. Returns the original if it's
     * already clickable, or the nearest clickable ancestor, or null if none exists.
     */
    fun resolveClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    /**
     * Captures a screenshot of the given display. Suspends until callback fires; returns
     * ScreenshotOutcome.Success(softwareBitmap) or Failure(reason). The success bitmap is a
     * software bitmap (ARGB_8888) — caller MUST call bitmap.recycle() when done to free
     * native memory. Returns Failure("api_too_low") on Android < 11 (API 30).
     */
    suspend fun captureScreenshot(displayId: Int): ScreenshotOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotOutcome.Failure("api_too_low")
        }
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(
                displayId,
                gestureExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val bmp = try {
                                Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            } finally {
                                result.hardwareBuffer.close()
                            }
                            if (cont.isActive) {
                                cont.resume(
                                    if (bmp != null) ScreenshotOutcome.Success(bmp)
                                    else ScreenshotOutcome.Failure("bitmap_decode_failed")
                                )
                            }
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resume(ScreenshotOutcome.Failure("exception:${t.message}"))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val reason = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "rate_limited"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no_access"
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal_error"
                            else -> "error_code_$errorCode"
                        }
                        if (cont.isActive) cont.resume(ScreenshotOutcome.Failure(reason))
                    }
                }
            )
        }
    }

    sealed class ScreenshotOutcome {
        data class Success(val bitmap: Bitmap) : ScreenshotOutcome()
        data class Failure(val reason: String) : ScreenshotOutcome()
    }

    companion object {
        @Volatile
        var instance: RikkaAccessibilityService? = null
            private set
    }
}
