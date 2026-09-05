package xyz.kbrowser.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.StateFlow
import xyz.kbrowser.webview.debug.KBDebug

interface KBWebView {
    val currentUrl: StateFlow<String?>
    val currentTitle: StateFlow<String?>
    val loadingState: StateFlow<LoadingState>
    val progress: StateFlow<Float> // 0.0f ~ 1.0f
    val canGoBack: StateFlow<Boolean>
    val canGoForward: StateFlow<Boolean>

    fun loadUrl(url: String)
    fun loadHtml(html: String)
    fun reload()
    fun stopLoading()
    fun goBack()
    fun goForward()

    fun evaluateJavascript(script: String, callback: ((String) -> Unit)? = null)

    /**
     * Registers a one-way (fire-and-forget) notification callback.
     * JS calls window.[name](data); the native side receives the data string, with no return value.
     *
     * Suited for analytics reporting, event notifications, logging, and other cases that
     * don't need to wait for a result.
     *
     * JS usage:
     * ```javascript
     * window.onUserAction("click");
     * ```
     */
    fun registerJsCallback(name: String, callback: (String) -> Unit)
    fun unregisterJsCallback(name: String)

    /**
     * Registers a bidirectional request handler with Promise support (request-response).
     * JS can `await window.[name](data)` and get the Kotlin handler's return value directly.
     *
     * Suited for cases where JS requests data, configuration, or computed results from native
     * and needs to wait for the response.
     *
     * Kotlin registration:
     * ```kotlin
     * webView.registerJsHandler("getConfig") { jsonString ->
     *     """{"theme":"dark","version":"1.0"}"""
     * }
     * ```
     *
     * JS usage (async/await supported):
     * ```javascript
     * const config = await window.getConfig(JSON.stringify({ key: "theme" }));
     * console.log(JSON.parse(config).theme); // "dark"
     * ```
     *
     * Note: the handler runs on a background thread; do not touch the UI from it.
     * Call [unregisterJsHandler] to unregister.
     */
    fun registerJsHandler(name: String, handler: (String) -> String)
    fun unregisterJsHandler(name: String)

    fun clearCacheAndCookies()

    fun setWebViewClient(client: KBWebViewClient?)
    fun setWebChromeClient(client: KBWebChromeClient?)

    fun destroy()

    /** Debug API: unified event stream, health snapshot, and a CDP escape hatch. */
    val debug: KBDebug

    /** Takes a screenshot of the page; screenshot pixels align 1:1 with CSS coordinates. */
    suspend fun takeScreenshot(): KBScreenshot?

    /**
     * Page background color. Defaults to black.
     * JVM Desktop: sets the background of both the outer Swing container and the CEF
     * rendering layer (works in OSR and non-OSR modes).
     * Android/iOS: no-op property; the background is determined by the WebView's own CSS.
     */
    var backgroundColor: Color

    /**
     * Callback for new window/tab requests.
     * Fired when a page requests a new window via target="_blank", window.open(), etc.
     * Once set, the default popup behavior is suppressed and the URL is left to the caller.
     * When unset, new window requests are silently dropped (nothing is opened).
     */
    var onNewWindowRequest: ((url: String) -> Unit)?

    /**
     * Callback for file dialog requests.
     * Invoked when a file selection is triggered via <input type="file"> or an upload button.
     *
     * JVM Desktop: when set, the caller returns file paths through the callback;
     * when unset, the request is silently cancelled (no native dialog is shown; it cannot be
     * shown in OSR mode).
     *
     * Android/iOS: no-op property; file uploads go through the platform's native flow.
     */
    var onFileDialogRequest: ((request: KBFileDialogRequest, callback: KBFileDialogCallback) -> Unit)?
}

@Composable
expect fun KBWebView(
    webView: KBWebView,
    modifier: Modifier = Modifier
)

@Composable
expect fun rememberKBWebView(
    initialUrl: String? = null,
    profile: KBProfile? = null
): KBWebView

/**
 * Creates a platform WebView.
 *
 * When [viewportWidth] / [viewportHeight] are non-null, the result is a background automation
 * page: no UI is attached and the render size is fixed to the viewport. When null, the size is
 * determined by the attached Compose modifier.
 */
internal expect fun createPageWebView(
    initialUrl: String? = null,
    profile: KBProfile? = null,
    viewportWidth: Int? = null,
    viewportHeight: Int? = null
): KBWebView

internal expect suspend fun performClickByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    popupSelector: String? = null
): Pair<Int, Int>?

internal expect suspend fun performHoverByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    popupSelector: String? = null
)

internal expect suspend fun verifyElementAtCdp(
    webView: KBWebView,
    vx: Int,
    vy: Int
): String?

internal expect suspend fun performScrollByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    deltaX: Int,
    deltaY: Int
)

internal expect suspend fun performDragByCoordinates(
    webView: KBWebView,
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int
)

internal expect suspend fun performClickByJs(
    webView: KBWebView,
    selector: String
)

internal expect suspend fun performHoverByJs(
    webView: KBWebView,
    selector: String
)

internal expect suspend fun performScrollByJs(
    webView: KBWebView,
    selector: String,
    deltaX: Int,
    deltaY: Int
)

internal expect suspend fun performDragByJs(
    webView: KBWebView,
    startSelector: String,
    endSelector: String
)

internal expect suspend fun performFocusByJs(
    webView: KBWebView,
    selector: String
)

internal expect suspend fun performKeyPress(
    webView: KBWebView,
    key: KeyboardKey
)

internal expect suspend fun performKeyCombination(
    webView: KBWebView,
    modifier: KeyboardKey,
    key: KeyboardKey
)

internal expect suspend fun performTypeChar(
    webView: KBWebView,
    char: Char
)

/**
 * Set files on an input[type=file] element directly via CDP DOM.setFileInputFiles.
 * This bypasses the file dialog entirely — no click, no dialog, no user gesture needed.
 *
 * JVM: uses CDP DOM.setFileInputFiles + dispatches 'change' event.
 * Android/iOS: throws UnsupportedOperationException (mobile uses native file dialog).
 */
internal expect suspend fun performSetFiles(
    webView: KBWebView,
    selector: String,
    filePaths: List<String>
)
