package xyz.kbrowser.webview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

class KBPage internal constructor(val webView: KBWebView) {
    val uuid: String = Random.nextLong().toString()
    
    val currentUrl: StateFlow<String?> get() = webView.currentUrl
    val title: StateFlow<String?> get() = webView.currentTitle
    val loadingState: StateFlow<LoadingState> get() = webView.loadingState
    val progress: StateFlow<Float> get() = webView.progress

    /**
     * Cache of node coordinates, refreshed on [getRawAxTree].
     */
    private val nodeCacheLock = Mutex()
    private var nodeCache: Map<String, AxNode> = emptyMap()

    suspend fun loadUrl(url: String) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val client = object : KBWebViewClient {
                    override fun onPageStarted(url: String) {}
                    override fun onPageFinished(url: String) {
                        webView.setWebViewClient(null)
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onReceivedError(error: Diagnostics) {
                        webView.setWebViewClient(null)
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(Exception("Failed to load page: ${error.description} (Code: ${error.errorCode})")))
                        }
                    }
                }
                
                continuation.invokeOnCancellation {
                    webView.setWebViewClient(null)
                    webView.stopLoading()
                }
                
                webView.setWebViewClient(client)
                webView.loadUrl(url)
            }
        }
    }

    suspend fun evaluateJavascript(script: String): String {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                webView.evaluateJavascript(script) { result ->
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }
            }
        }
    }

    suspend fun clearCacheAndCookies() {
        withContext(Dispatchers.Main) {
            webView.clearCacheAndCookies()
        }
    }

    suspend fun setCookieViaJs(cookieString: String) {
        val escapedCookie = cookieString.replace("\"", "\\\"")
        evaluateJavascript("document.cookie = \"$escapedCookie\";")
    }

    suspend fun getRawAxTree(): AxTreeData {
        val json = evaluateJavascript(JsScripts.EXTRACT_SNAPSHOT)
        val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val treeData = jsonParser.decodeFromString<AxTreeData>(json)
        // Update node coordinates cache
        val newCache = treeData.nodes.associateBy { it.refid }
        nodeCacheLock.withLock {
            nodeCache = newCache
        }
        return treeData
    }

    /**
     * Clicks the element with the specified [refid] using coordinate-based interaction.
     *
     * @throws ElementNotFoundException if [refid] is not found in the cache
     */
    suspend fun click(refid: String) {
        val node = nodeCacheLock.withLock { nodeCache[refid] }
            ?: throw ElementNotFoundException(refid)
        clickByCoordinates(node.centerX, node.centerY)
    }

    /**
     * Hovers over the element with the specified [refid] using coordinate-based interaction.
     *
     * @throws ElementNotFoundException if [refid] is not found in the cache
     */
    suspend fun hover(refid: String) {
        val node = nodeCacheLock.withLock { nodeCache[refid] }
            ?: throw ElementNotFoundException(refid)
        hoverByCoordinates(node.centerX, node.centerY)
    }

    /**
     * Scrolls the element with the specified [refid] using coordinate-based interaction.
     *
     * @throws ElementNotFoundException if [refid] is not found in the cache
     */
    suspend fun scroll(refid: String, deltaX: Int, deltaY: Int) {
        val node = nodeCacheLock.withLock { nodeCache[refid] }
            ?: throw ElementNotFoundException(refid)
        scrollByCoordinates(node.centerX, node.centerY, deltaX, deltaY)
    }

    suspend fun clickByCoordinates(x: Int, y: Int) {
        performClickByCoordinates(webView, x, y)
    }

    suspend fun hoverByCoordinates(x: Int, y: Int) {
        performHoverByCoordinates(webView, x, y)
    }

    suspend fun scrollByCoordinates(x: Int, y: Int, deltaX: Int, deltaY: Int) {
        performScrollByCoordinates(webView, x, y, deltaX, deltaY)
    }

    suspend fun dragByCoordinates(startX: Int, startY: Int, endX: Int, endY: Int) {
        performDragByCoordinates(webView, startX, startY, endX, endY)
    }

    // ===== Native Key Event Methods =====

    suspend fun press(key: KeyboardKey) {
        performKeyPress(webView, key)
    }

    suspend fun pressKeyCombination(modifier: KeyboardKey, key: KeyboardKey) {
        performKeyCombination(webView, modifier, key)
    }

    suspend fun typeChar(char: Char) {
        performTypeChar(webView, char)
    }

    suspend fun type(text: String) {
        for (char in text) {
            typeChar(char)
            kotlinx.coroutines.delay(Random.nextLong(30, 150))
        }
    }

    // ===== KBLocator Factory Methods =====

    /**
     * Creates a [KBLocator] for the given selector.
     * Supports "css=" and "xpath=" prefixes. Defaults to CSS.
     */
    fun locator(selector: String): KBLocator {
        return if (selector.startsWith("xpath=")) {
            KBLocator(this, selector.removePrefix("xpath="), KBSelectorType.XPATH)
        } else {
            KBLocator(this, selector.removePrefix("css="), KBSelectorType.CSS)
        }
    }

    fun getByRole(role: String, name: String? = null): KBLocator {
        return KBLocator(this, role, KBSelectorType.ROLE, name = name)
    }

    fun getByText(text: String, exact: Boolean = true): KBLocator {
        return KBLocator(this, text, KBSelectorType.TEXT, exact = exact)
    }

    fun getByLabel(label: String): KBLocator {
        return KBLocator(this, label, KBSelectorType.LABEL)
    }

    fun getByPlaceholder(text: String): KBLocator {
        return KBLocator(this, text, KBSelectorType.PLACEHOLDER)
    }

    fun getByAltText(text: String): KBLocator {
        return KBLocator(this, text, KBSelectorType.ALT_TEXT)
    }

    fun getByTitle(title: String): KBLocator {
        return KBLocator(this, title, KBSelectorType.TITLE)
    }

    fun getByTestId(testId: String): KBLocator {
        return KBLocator(this, testId, KBSelectorType.TEST_ID)
    }

    fun close() {
        webView.destroy()
    }
}
