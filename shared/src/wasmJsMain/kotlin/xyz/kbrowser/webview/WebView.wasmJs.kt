@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package xyz.kbrowser.webview

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.HTMLIFrameElement
import xyz.kbrowser.webview.debug.KBDebug
import xyz.kbrowser.webview.debug.KBDebugNoop

class WasmKBWebView(
    initialUrl: String? = null,
    val profile: KBProfile? = null
) : KBWebView {
    private val _currentUrl = MutableStateFlow<String?>(initialUrl)
    override val currentUrl: StateFlow<String?> = _currentUrl.asStateFlow()

    private val _currentTitle = MutableStateFlow<String?>(null)
    override val currentTitle: StateFlow<String?> = _currentTitle.asStateFlow()

    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Initializing)
    override val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    override val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    override val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)
    override val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    override var backgroundColor: Color = Color.White
    override var onNewWindowRequest: ((url: String) -> Unit)? = null
    override var onFileDialogRequest: ((request: KBFileDialogRequest, callback: KBFileDialogCallback) -> Unit)? = null

    private var client: KBWebViewClient? = null
    private var chromeClient: KBWebChromeClient? = null

    internal var iframeElement: HTMLIFrameElement? = null
    private var pendingHtml: String? = null
    private var pendingUrl: String? = initialUrl
    private val jsCallbacks = mutableMapOf<String, (String) -> Unit>()
    private val jsHandlers = mutableMapOf<String, (String) -> String>()

    override val debug: KBDebug = KBDebugNoop

    internal fun bindIFrame(element: HTMLIFrameElement) {
        consoleLog("[WasmKBWebView] bindIFrame attached. Has pendingHtml=${pendingHtml != null}")
        this.iframeElement = element
        element.onload = {
            consoleLog("[WasmKBWebView] iframe onload triggered")
            _loadingState.value = LoadingState.Finished
            _progress.value = 1.0f
            try {
                _currentTitle.value = element.contentDocument?.title ?: ""
            } catch (e: Throwable) {}
            reinitJsBindings()
            client?.onPageFinished(_currentUrl.value ?: "")
        }
        pendingHtml?.let { html ->
            consoleLog("[WasmKBWebView] Applying pendingHtml len=${html.length}")
            setIframeContent(element, html)
            pendingHtml = null
        } ?: pendingUrl?.let { url ->
            consoleLog("[WasmKBWebView] Applying pendingUrl=$url")
            element.src = url
            pendingUrl = null
        }
    }

    internal fun unbindIFrame() {
        consoleLog("[WasmKBWebView] unbindIFrame called")
        this.iframeElement = null
    }

    private fun setIframeContent(iframe: HTMLIFrameElement, html: String) {
        consoleLog("[WasmKBWebView] setIframeContent called, html len=${html.length}")
        try {
            iframe.setAttribute("srcdoc", html)
        } catch (e: Throwable) {
            consoleLog("[WasmKBWebView] setAttribute srcdoc failed, fallback data URL: ${e.message}")
            iframe.src = "data:text/html;charset=utf-8," + encodeUriComponent(html)
        }
    }

    override fun loadUrl(url: String) {
        consoleLog("[WasmKBWebView] loadUrl: $url")
        _currentUrl.value = url
        _loadingState.value = LoadingState.Loading
        _progress.value = 0.1f
        client?.onPageStarted(url)

        val iframe = iframeElement
        if (iframe != null) {
            iframe.removeAttribute("srcdoc")
            iframe.src = url
        } else {
            pendingUrl = url
            pendingHtml = null
        }
    }

    override fun loadHtml(html: String) {
        consoleLog("[WasmKBWebView] loadHtml called len=${html.length}, iframeIsBound=${iframeElement != null}")
        _currentUrl.value = "about:blank"
        _loadingState.value = LoadingState.Loading
        _progress.value = 0.1f
        client?.onPageStarted("about:blank")

        val iframe = iframeElement
        if (iframe != null) {
            setIframeContent(iframe, html)
        } else {
            pendingHtml = html
            pendingUrl = null
        }
    }

    override fun reload() {
        iframeElement?.contentWindow?.location?.reload()
    }

    override fun stopLoading() {
        iframeElement?.contentWindow?.stop()
        _loadingState.value = LoadingState.Finished
    }

    override fun goBack() {
        iframeElement?.contentWindow?.history?.back()
    }

    override fun goForward() {
        iframeElement?.contentWindow?.history?.forward()
    }

    override fun evaluateJavascript(script: String, callback: ((String) -> Unit)?) {
        try {
            val win = iframeElement?.contentWindow
            val result = if (win != null) evalInWindow(win, script) else ""
            callback?.invoke(result)
        } catch (e: Throwable) {
            callback?.invoke("")
        }
    }

    override fun registerJsCallback(name: String, callback: (String) -> Unit) {
        jsCallbacks[name] = callback
        val win = iframeElement?.contentWindow ?: return
        installJsCallback(win, name, callback)
    }

    override fun unregisterJsCallback(name: String) {
        jsCallbacks.remove(name)
        val win = iframeElement?.contentWindow ?: return
        removeJsProperty(win, name)
    }

    override fun registerJsHandler(name: String, handler: (String) -> String) {
        jsHandlers[name] = handler
        val win = iframeElement?.contentWindow ?: return
        installJsHandler(win, name, handler)
    }

    override fun unregisterJsHandler(name: String) {
        jsHandlers.remove(name)
        val win = iframeElement?.contentWindow ?: return
        removeJsProperty(win, name)
    }

    private fun reinitJsBindings() {
        val win = iframeElement?.contentWindow ?: return
        jsCallbacks.forEach { (n, cb) -> installJsCallback(win, n, cb) }
        jsHandlers.forEach { (n, h) -> installJsHandler(win, n, h) }
    }

    override fun clearCacheAndCookies() {}

    override fun setWebViewClient(client: KBWebViewClient?) {
        this.client = client
    }

    override fun setWebChromeClient(client: KBWebChromeClient?) {
        this.chromeClient = client
    }

    override fun destroy() {
        iframeElement?.remove()
        iframeElement = null
        _loadingState.value = LoadingState.Finished
    }

    override suspend fun takeScreenshot(): KBScreenshot? {
        return null
    }
}

@Composable
actual fun KBWebView(
    webView: KBWebView,
    modifier: Modifier
) {
    val wasmWebView = webView as? WasmKBWebView ?: return

    DisposableEffect(wasmWebView) {
        val element = document.createElement("iframe") as HTMLIFrameElement
        element.style.position = "absolute"
        element.style.border = "none"
        element.style.zIndex = "10"
        element.style.backgroundColor = "white"

        document.body?.appendChild(element)
        wasmWebView.bindIFrame(element)

        onDispose {
            wasmWebView.unbindIFrame()
            element.remove()
        }
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            val iframe = wasmWebView.iframeElement ?: return@onGloballyPositioned

            val dpr = window.devicePixelRatio
            val canvas = document.getElementById("ComposeTarget") ?: document.querySelector("canvas")
            val canvasRect = canvas?.getBoundingClientRect()

            val canvasLeft = canvasRect?.left ?: 0.0
            val canvasTop = canvasRect?.top ?: 0.0

            val leftPx = canvasLeft + (bounds.left / dpr)
            val topPx = canvasTop + (bounds.top / dpr)
            val widthPx = bounds.width / dpr
            val heightPx = bounds.height / dpr

            iframe.style.left = "${leftPx}px"
            iframe.style.top = "${topPx}px"
            iframe.style.width = "${widthPx}px"
            iframe.style.height = "${heightPx}px"
        }
    )
}

@Composable
actual fun rememberKBWebView(
    initialUrl: String?,
    profile: KBProfile?
): KBWebView {
    return remember(initialUrl, profile) {
        WasmKBWebView(initialUrl = initialUrl, profile = profile)
    }
}

internal actual fun createHeadlessWebView(
    initialUrl: String?,
    profile: KBProfile?,
    viewportWidth: Int?,
    viewportHeight: Int?,
    headless: Boolean
): KBWebView {
    return WasmKBWebView(initialUrl = initialUrl, profile = profile)
}

internal actual suspend fun performClickByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    popupSelector: String?
): Pair<Int, Int>? {
    return null
}

internal actual suspend fun performHoverByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    popupSelector: String?
) {}

internal actual suspend fun verifyElementAtCdp(
    webView: KBWebView,
    vx: Int,
    vy: Int
): String? = null

internal actual suspend fun performScrollByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    deltaX: Int,
    deltaY: Int
) {}

internal actual suspend fun performDragByCoordinates(
    webView: KBWebView,
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int
) {}

internal actual suspend fun performClickByJs(
    webView: KBWebView,
    selector: String
) {}

internal actual suspend fun performHoverByJs(
    webView: KBWebView,
    selector: String
) {}

internal actual suspend fun performScrollByJs(
    webView: KBWebView,
    selector: String,
    deltaX: Int,
    deltaY: Int
) {}

internal actual suspend fun performDragByJs(
    webView: KBWebView,
    startSelector: String,
    endSelector: String
) {}

internal actual suspend fun performFocusByJs(
    webView: KBWebView,
    selector: String
) {}

internal actual suspend fun performKeyPress(
    webView: KBWebView,
    key: KeyboardKey
) {}

internal actual suspend fun performKeyCombination(
    webView: KBWebView,
    modifier: KeyboardKey,
    key: KeyboardKey
) {}

internal actual suspend fun performTypeChar(
    webView: KBWebView,
    char: Char
) {}

internal actual suspend fun performSetFiles(
    webView: KBWebView,
    selector: String,
    filePaths: List<String>
) {
    throw UnsupportedOperationException("performSetFiles is not supported on WasmJS platform.")
}

internal actual fun setInteractionLockedNative(webView: KBWebView, locked: Boolean) {}

internal actual fun updateMouseTrailNative(webView: KBWebView, viewportX: Int, viewportY: Int) {}

internal actual fun performGlobalShutdown() {}

internal actual suspend fun fetchAxTreeNative(webView: KBWebView): AxTreeData? = null

internal actual suspend fun findElementsNative(
    webView: KBWebView,
    selector: String,
    selectorType: KBSelectorType,
    name: String?,
    exact: Boolean
): List<LocateResult>? = null

actual suspend fun initializeKBrowser() {}

actual fun showScreenshotPreview(bytes: ByteArray) {}

private fun evalInWindow(win: org.w3c.dom.Window, script: String): String = js("win.eval(script)")
private fun installJsCallback(win: org.w3c.dom.Window, name: String, callback: (String) -> Unit) {
    js("win[name] = function(data) { callback(data); }")
}
private fun installJsHandler(win: org.w3c.dom.Window, name: String, handler: (String) -> String) {
    js("win[name] = function(data) { return handler(data); }")
}
private fun removeJsProperty(win: org.w3c.dom.Window, name: String) {
    js("delete win[name]")
}
private fun consoleLog(msg: String) {
    js("console.log(msg)")
}
private fun encodeUriComponent(str: String): String = js("encodeURIComponent(str)")
