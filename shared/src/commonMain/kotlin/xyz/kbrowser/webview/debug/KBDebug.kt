package xyz.kbrowser.webview.debug

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Debug API for AI-driven browser automation.
 *
 * Provides a unified event stream, point-in-time health snapshots,
 * on-demand network response body retrieval, and raw CDP escape hatch.
 *
 * Usage:
 * ```kotlin
 * webView.debug.enable()
 * page.click(refid)
 * delay(500)
 * val snap = webView.debug.snapshot()
 * if (snap.consoleErrorCount > 0) { /* AI: handle errors */ }
 * val events = webView.debug.events.replayCache // recent events
 * ```
 *
 * JVM: fully backed by CDP (Chrome DevTools Protocol) via CefDevToolsClient.
 * Android/iOS/Fallback: KBDebugNoop (empty flows, null returns).
 */
interface KBDebug {

    /**
     * Enable debug capture. After this call:
     * - Console logs, JS exceptions, network requests, and render crashes flow into [events].
     * - Render process crash auto-recovery is activated.
     *
     * Must be called before the first action you want to capture.
     * Idempotent — calling multiple times is safe.
     */
    fun enable()

    /**
     * Disable debug capture and release CDP resources.
     * The [events] replay buffer is cleared.
     */
    fun disable()

    /**
     * Unified debug event stream.
     *
     * Internal ring buffer (capacity 500, drop-oldest). Even without continuous
     * collection, the most recent events are available via [replayCache] or [snapshot].
     *
     * Event types: [DebugEvent.ConsoleLog], [DebugEvent.JsException],
     * [DebugEvent.NetworkRequest], [DebugEvent.RenderCrash].
     */
    val events: SharedFlow<DebugEvent>

    /**
     * Current page health snapshot.
     *
     * Returns performance metrics (JS heap, DOM node count), error counts,
     * crash status, and a list of recent error events.
     *
     * Ideal for post-action diagnostics: `page.click(refid) → delay → snapshot()`.
     */
    suspend fun snapshot(): DebugSnapshot

    /**
     * Retrieve a network response body by requestId.
     *
     * requestId comes from [DebugEvent.NetworkRequest] events in the [events] stream.
     * CDP internally caches response bodies with eviction — call soon after the
     * request completes for best results.
     *
     * @return response body string, or null if unavailable (JVM only; mobile returns null)
     */
    suspend fun getResponseBody(requestId: String): String?

    /**
     * Execute a raw CDP (Chrome DevTools Protocol) method.
     *
     * Escape hatch for capabilities not wrapped by this API.
     *
     * @param method CDP method name, e.g. "Runtime.evaluate", "DOM.getDocument"
     * @param params JSON parameter string, e.g. """{"expression":"1+1"}"""
     * @return JSON result string, or null (JVM only; mobile returns null)
     */
    suspend fun executeCdp(method: String, params: String): String?

    /**
     * Subscribe to raw CDP events (unparsed method + params).
     *
     * Advanced usage. For parsed events, use [events] instead.
     */
    fun onCdpEvent(listener: (method: String, params: String) -> Unit)
}

// ── Event Model ─────────────────────────────────────────────────────────

/**
 * Unified debug event. All events carry a [timestamp] (epoch millis).
 */
sealed class DebugEvent {
    abstract val timestamp: Long

    /**
     * console.log/info/warn/error/debug output from page JavaScript.
     */
    data class ConsoleLog(
        override val timestamp: Long,
        val level: ConsoleLevel,
        val text: String,
        val url: String? = null,
        val lineNo: Int? = null,
        val columnNo: Int? = null,
        val stackTrace: String? = null
    ) : DebugEvent()

    /**
     * Uncaught JavaScript exception (distinct from console.error).
     * Maps to CDP Runtime.exceptionThrown.
     */
    data class JsException(
        override val timestamp: Long,
        val message: String,
        val sourceUrl: String? = null,
        val lineNo: Int? = null,
        val columnNo: Int? = null,
        val stackTrace: String? = null
    ) : DebugEvent()

    /**
     * Completed or failed network request.
     * One event per request lifecycle (not per CDP lifecycle event).
     */
    data class NetworkRequest(
        override val timestamp: Long,
        val requestId: String,
        val url: String,
        val method: String,
        val status: Int? = null,
        val mimeType: String? = null,
        val resourceType: String? = null,
        val encodedDataLength: Long? = null,
        val failed: Boolean = false,
        val errorText: String? = null
    ) : DebugEvent()

    /**
     * Render process crash event.
     * Auto-recovery (reload) is handled by [KBDebug.enable].
     */
    data class RenderCrash(
        override val timestamp: Long,
        val status: String,
        val errorCode: Int,
        val errorString: String? = null
    ) : DebugEvent()
}

enum class ConsoleLevel { LOG, INFO, WARNING, ERROR, DEBUG }

// ── Snapshot Model ──────────────────────────────────────────────────────

/**
 * Point-in-time page health summary.
 */
data class DebugSnapshot(
    val jsHeapUsedSize: Long,
    val jsHeapTotalSize: Long,
    val domNodeCount: Int,
    val jsEventListeners: Int,
    val documents: Int,
    val consoleErrorCount: Int,
    val jsExceptionCount: Int,
    val failedRequestCount: Int,
    val totalRequestCount: Int,
    val crashedRecently: Boolean,
    val recentErrors: List<DebugEvent>
)

// ── No-op Implementation ─────────────────────────────────────────────────

/**
 * No-op implementation for platforms without CDP (Android, iOS, fallback).
 */
@OptIn(ExperimentalCoroutinesApi::class)
object KBDebugNoop : KBDebug {
    private val _events = MutableSharedFlow<DebugEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<DebugEvent> = _events.asSharedFlow()

    override fun enable() {}
    override fun disable() {}
    override suspend fun snapshot(): DebugSnapshot = DebugSnapshot(
        jsHeapUsedSize = 0,
        jsHeapTotalSize = 0,
        domNodeCount = 0,
        jsEventListeners = 0,
        documents = 0,
        consoleErrorCount = 0,
        jsExceptionCount = 0,
        failedRequestCount = 0,
        totalRequestCount = 0,
        crashedRecently = false,
        recentErrors = emptyList()
    )

    override suspend fun getResponseBody(requestId: String): String? = null
    override suspend fun executeCdp(method: String, params: String): String? = null
    override fun onCdpEvent(listener: (method: String, params: String) -> Unit) {}
}
