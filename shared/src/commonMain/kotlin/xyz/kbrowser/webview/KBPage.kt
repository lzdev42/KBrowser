package xyz.kbrowser.webview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.random.Random

class KBPage(val webView: KBWebView) {
    val uuid: String = Random.nextLong().toString()
    
    val currentUrl: StateFlow<String?> get() = webView.currentUrl
    val title: StateFlow<String?> get() = webView.currentTitle
    val loadingState: StateFlow<LoadingState> get() = webView.loadingState
    val progress: StateFlow<Float> get() = webView.progress

    /**
     * Cache of node coordinates, refreshed on [getRawAxTree].
     *
     * @Volatile 保证读操作对所有线程立即可见，无需加锁，
     * 因此 click 等读操作永远不会因为 getRawAxTree 写入时持锁而死锁。
     * 写操作通过 nodeCacheWriteLock 串行化，防止并发写入导致数据竞争。
     */
    private val nodeCacheWriteLock = Mutex()
    @Volatile private var nodeCache: Map<String, AxNode> = emptyMap()

    private suspend fun updateNodeCache(newCache: Map<String, AxNode>) {
        nodeCacheWriteLock.withLock {
            nodeCache = newCache
        }
    }

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
        // JVM 平台优先走 CDP 原生路线（不注入 JS，不受 CSP 限制）
        val nativeTree = fetchAxTreeNative(webView)
        if (nativeTree != null) {
            updateNodeCache(nativeTree.nodes.associateBy { it.refid })
            return nativeTree
        }
        // fallback：JS 注入路线（Android / iOS）
        val json = evaluateJavascript(JsScripts.EXTRACT_SNAPSHOT)
        val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val treeData = jsonParser.decodeFromString<AxTreeData>(json)
        updateNodeCache(treeData.nodes.associateBy { it.refid })
        return treeData
    }

    /**
     * Clicks the element with the specified [refid] using coordinate-based interaction.
     *
     * @throws ElementNotFoundException if [refid] is not found in the cache
     */
    suspend fun click(refid: String) {
        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        clickByCoordinates(node.centerX, node.centerY)
    }

    /**
     * Hovers over the element with the specified [refid] using coordinate-based interaction.
     *
     * @throws ElementNotFoundException if [refid] is not found in the cache
     */
    suspend fun hover(refid: String) {
        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        hoverByCoordinates(node.centerX, node.centerY)
    }

    /**
     * Scrolls the element with the specified [refid] using coordinate-based interaction.
     *
     * @throws ElementNotFoundException if [refid] is not found in the cache
     */
    suspend fun scroll(refid: String, deltaX: Int, deltaY: Int) {
        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
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

    suspend fun screenshot(): ByteArray? {
        return webView.takeScreenshot()
    }

    /**
     * 锁定/解锁用户交互。
     * locked=true：在浏览器上覆盖 AWT 拦截层，阻止用户鼠标/键盘输入，并显示鼠标轨迹。
     * locked=false：移除拦截层，恢复用户操作。
     * 自动化操作（CDP）不受影响。JVM 平台有效，Android/iOS 为空实现。
     */
    fun setInteractionLocked(locked: Boolean) {
        webView.setInteractionLocked(locked)
    }

    /**
     * 更新鼠标轨迹位置（在锁定状态下显示自动化操作的光标动画）。
     * 坐标为视口坐标（CSS 像素）。仅 JVM 平台有效。
     */
    fun updateMouseTrail(viewportX: Int, viewportY: Int) {
        webView.updateMouseTrail(viewportX, viewportY)
    }

    /**
     * Returns the current page state as a KBrowser YAML Snapshot string.
     *
     * Pure JSON→YAML conversion of the AXTree — no filtering, no truncation.
     * All node fields and attributes are preserved.
     * Use [toYamlSnapshot] with `clean = true` for a cleaned version.
     *
     * Example output:
     * ```
     * url: "https://example.com"
     * innerWidth: 1920
     * ...
     * nodes:
     *   - refid: "r1"
     *     tagName: "html"
     *     role: "document"
     *     ...
     * ```
     */
    suspend fun snapshot(clean: Boolean = true): String {
        val rawTree = getRawAxTree()
        return rawTree.toYamlSnapshot(clean)
    }

    /**
     * 新标签页/新窗口请求回调。
     * 当页面通过 target="_blank"、window.open() 等方式请求打开新窗口时触发。
     * 直接代理到底层 [KBWebView.onNewWindowRequest]。
     *
     * 示例
     * ```kotlin
     * page.onNewPage = { url -> println("需要打开新页面: $url") }
     * ```
     */
    var onNewPage: ((url: String) -> Unit)?
        get() = webView.onNewWindowRequest
        set(value) { webView.onNewWindowRequest = value }

    fun close() {
        webView.destroy()
    }
}
