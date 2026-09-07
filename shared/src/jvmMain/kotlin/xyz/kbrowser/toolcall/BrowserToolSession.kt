package xyz.kbrowser.toolcall

import kotlinx.serialization.json.JsonObject

/**
 * Transport-neutral AI tool-call layer for KBrowser.
 *
 * [BrowserTools] defines the tool surface (specs + handlers) that an embedding host
 * registers into its own agent runtime. It knows nothing about transports: the demo
 * app binds it to MCP stdio, BrowserPilot binds it directly into its in-process
 * agent tool registry. Everything AI-facing (schema, dispatch, error-as-data,
 * occlusion fallback) lives here; everything host-specific (tab lifecycle policy,
 * viewport sizes, logging) is injected via [BrowserToolSession].
 */

/** One content block of a tool result. */
sealed class ToolContent {
    data class Text(val text: String) : ToolContent()
    data class Image(val data: String, val mimeType: String) : ToolContent()
}

/** Result of a tool invocation. [isError] marks recoverable failures the AI should read. */
data class ToolResult(val content: List<ToolContent>, val isError: Boolean = false)

/** A tool definition: name + description + JSON Schema for the arguments. */
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

/** A tab as seen by the tool layer. */
data class ToolTabInfo(
    val pageId: String,
    val url: String,
    val title: String,
    val active: Boolean,
    val idleMs: Long
)

/**
 * Host-side session the tools run against: tab lifecycle + ownership policy.
 * Implement this in the embedding host (e.g. back it with an existing tab-ownership
 * system); BrowserTools never creates or destroys pages on its own.
 */
interface BrowserToolSession {
    /**
     * Resolves a page by [pageId]; null means "active tab" (host-defined, usually the
     * most recently used). Throws [IllegalArgumentException] on unknown pageId and
     * [IllegalStateException] when no tab exists yet.
     */
    fun resolve(pageId: String?): xyz.kbrowser.webview.KBPage

    /** Opens a new managed tab; loads [url] when not null/blank. */
    suspend fun newTab(url: String?): xyz.kbrowser.webview.KBPage

    /** Closes the tab with [pageId]; returns the closed id. Refuse to close the last tab. */
    suspend fun closeTab(pageId: String?): String

    /** Closes every tab except the active one; returns the closed ids. */
    suspend fun closeOthers(): List<String>

    /** Snapshot of all open tabs. */
    fun listTabs(): List<ToolTabInfo>

    /** Host logging hook (optional). */
    fun onLog(msg: String) {}
}
