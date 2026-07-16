package xyz.kbrowser.webview.debug

import kotlinx.coroutines.flow.SharedFlow

/**
 * Debug API for AI-driven browser automation.
 *
 * Provides a query-based inspection interface, dialog handling,
 * on-demand network response body retrieval, and raw CDP escape hatch.
 *
 * Designed for MCP/toolcall usage: every method returns data directly
 * (no event subscription needed).
 *
 * Usage:
 * ```kotlin
 * webView.debug.enable()
 * page.click(refid)
 * val inspection = webView.debug.inspect()   // what happened?
 * if (inspection.activeDialog != null) {
 *     webView.debug.respondDialog(accept = true)
 * }
 * ```
 *
 * JVM: fully backed by CDP (Chrome DevTools Protocol) via CefDevToolsClient.
 * Android/iOS/Fallback: KBDebugNoop (empty results, null returns).
 */
interface KBDebug {

    /**
     * Enable debug capture. After this call:
     * - Console errors, JS exceptions, network requests, navigations, and dialogs are tracked.
     * - Render process crash auto-recovery is activated.
     * - JS dialogs are intercepted (no native dialog shown) — handle via [respondDialog].
     *
     * Must be called before the first action you want to capture.
     * Idempotent — calling multiple times is safe.
     */
    fun enable()

    /**
     * Disable debug capture and release CDP resources.
     * Internal event buffer is cleared.
     */
    fun disable()

    /**
     * Whether debug mode is currently active.
     * WebView checks this to decide whether to auto-dismiss JS dialogs.
     */
    val isEnabled: Boolean

    /**
     * Query: what happened since the last [inspect] call?
     *
     * Returns the current page URL, whether the page navigated since the last
     * inspect, any active JS dialog, errors that occurred, and XHR/Fetch requests
     * that were made.
     *
     * Acts as an implicit checkpoint — the next [inspect] only returns events
     * that occur after this call.
     */
    suspend fun inspect(): PageInspection

    /**
     * Respond to the active JS dialog (alert/confirm/prompt).
     *
     * Use after [inspect] reports a non-null [PageInspection.activeDialog].
     *
     * @param accept true to accept (OK/confirm), false to dismiss (cancel)
     * @param promptText text to enter for prompt dialogs (ignored for alert/confirm)
     * @return true if a dialog was present and handled; false if no dialog was active
     */
    suspend fun respondDialog(accept: Boolean, promptText: String? = null): Boolean

    /**
     * Point-in-time page health snapshot.
     *
     * Returns performance metrics (JS heap, DOM node count), error counts,
     * crash status, and a list of recent error events.
     *
     * Ideal for periodic health monitoring (not post-action queries — use [inspect] for that).
     */
    suspend fun snapshot(): DebugSnapshot

    /**
     * Retrieve a network response body by requestId.
     *
     * requestId comes from [PageInspection.requests] or [DebugSnapshot.recentErrors].
     * CDP internally caches response bodies with eviction — call soon after the
     * request completes for best results.
     *
     * @return response body string, or null if unavailable
     */
    suspend fun getResponseBody(requestId: String): String?

    /**
     * Execute a raw CDP (Chrome DevTools Protocol) method.
     *
     * Escape hatch for capabilities not wrapped by this API.
     *
     * @param method CDP method name, e.g. "Runtime.evaluate", "DOM.getDocument"
     * @param params JSON parameter string, e.g. """{"expression":"1+1"}"""
     * @return JSON result string, or null
     */
    suspend fun executeCdp(method: String, params: String): String?
}

// ── Inspection Result ────────────────────────────────────────────────────

/**
 * Result of [KBDebug.inspect] — answers "what happened since last check?".
 */
data class PageInspection(
    val currentUrl: String,
    val navigated: Boolean,
    val activeDialog: DialogInfo? = null,
    val errors: List<PageError> = emptyList(),
    val requests: List<RequestSummary> = emptyList()
)

data class DialogInfo(
    val type: DialogType,
    val message: String,
    val defaultPrompt: String? = null
)

data class PageError(
    val type: String,
    val message: String
)

data class RequestSummary(
    val requestId: String,
    val method: String,
    val url: String,
    val status: Int?,
    val failed: Boolean = false
)

enum class DialogType { ALERT, CONFIRM, PROMPT, BEFOREUNLOAD }

// ── Internal Event Model (for KBDebugJvm internal use) ────────────────────

/**
 * Internal event type for recording debug events in the ring buffer.
 */
sealed class DebugEvent {
    abstract val timestamp: Long

    data class ConsoleLog(
        override val timestamp: Long,
        val level: ConsoleLevel,
        val text: String,
        val url: String? = null,
        val lineNo: Int? = null,
        val columnNo: Int? = null,
        val stackTrace: String? = null
    ) : DebugEvent()

    data class JsException(
        override val timestamp: Long,
        val message: String,
        val sourceUrl: String? = null,
        val lineNo: Int? = null,
        val columnNo: Int? = null,
        val stackTrace: String? = null
    ) : DebugEvent()

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

    data class RenderCrash(
        override val timestamp: Long,
        val status: String,
        val errorCode: Int,
        val errorString: String? = null
    ) : DebugEvent()

    data class Navigation(
        override val timestamp: Long,
        val url: String
    ) : DebugEvent()

    data class Dialog(
        override val timestamp: Long,
        val type: DialogType,
        val message: String,
        val url: String? = null,
        val defaultPrompt: String? = null
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
object KBDebugNoop : KBDebug {
    override fun enable() {}
    override fun disable() {}
    override val isEnabled: Boolean get() = false
    override suspend fun inspect(): PageInspection = PageInspection(
        currentUrl = "",
        navigated = false
    )
    override suspend fun respondDialog(accept: Boolean, promptText: String?): Boolean = false
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
}
