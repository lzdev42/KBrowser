package xyz.kbrowser.webview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.delay
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
        val treeData = if (nativeTree != null) {
            nativeTree
        } else {
            // fallback：JS 注入路线（Android / iOS）
            val json = evaluateJavascript(JsScripts.EXTRACT_SNAPSHOT)
            val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            jsonParser.decodeFromString<AxTreeData>(json)
        }
        updateNodeCache(treeData.nodes.associateBy { it.refid })
        return treeData
    }

    /**
     * Clicks the element with the specified [refid] using coordinate-based interaction.
     *
     * @throws ElementNotFoundException if [refid] is not found in the cache
     */
    suspend fun click(refid: String): OperationResult {
        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        clickByCoordinates(node.centerX, node.centerY)
        delay(200)
        return verifyClickAt(node.centerX, node.centerY, node)
    }

    /**
     * Clicks the element with the specified [refid] using JS-based interaction.
     *
     * @throws ElementNotFoundException if [refid] is not found in the cache
     */
    suspend fun jsClick(refid: String) {
        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        performClickByJs(webView, node.selector)
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
     * Hovers over the element with the specified [refid] using JS-based interaction.
     *
     * @throws ElementNotFoundException if [refid] is not found in the cache
     */
    suspend fun jsHover(refid: String) {
        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        performHoverByJs(webView, node.selector)
    }

    /**
     * Scrolls the element with the specified [refid] using coordinate-based interaction.
     *
     * @throws ElementNotFoundException if [refid] is not found in the cache
     */
    suspend fun scroll(refid: String, deltaX: Int, deltaY: Int): OperationResult {
        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        val scrollTopBefore = evaluateJavascript(
            "(function(){var e=document.querySelector('${node.selector.replace("'", "\\'")}');return String(e?e.scrollTop:window.scrollY)})()"
        ).trim().toDoubleOrNull() ?: 0.0
        scrollByCoordinates(node.centerX, node.centerY, deltaX, deltaY)
        delay(300)
        val scrollTopAfter = evaluateJavascript(
            "(function(){var e=document.querySelector('${node.selector.replace("'", "\\'")}');return String(e?e.scrollTop:window.scrollY)})()"
        ).trim().toDoubleOrNull() ?: 0.0
        return if (scrollTopAfter != scrollTopBefore) {
            OperationResult.Success("scroll", detail = "scrollTop: $scrollTopBefore → $scrollTopAfter")
        } else {
            OperationResult.Failure("scroll", "scrollTop unchanged ($scrollTopBefore)")
        }
    }

    /**
     * Scrolls the element with the specified [refid] using JS-based interaction.
     *
     * @throws ElementNotFoundException if [refid] is not found in the cache
     */
    suspend fun jsScroll(refid: String, deltaX: Int, deltaY: Int) {
        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        performScrollByJs(webView, node.selector, deltaX, deltaY)
    }

    /**
     * Drags from start element to end element using coordinate-based interaction.
     *
     * @throws ElementNotFoundException if start or end refid is not found in the cache
     */
    suspend fun drag(startRefid: String, endRefid: String) {
        val startNode = nodeCache[startRefid] ?: throw ElementNotFoundException(startRefid)
        val endNode = nodeCache[endRefid] ?: throw ElementNotFoundException(endRefid)
        dragByCoordinates(startNode.centerX, startNode.centerY, endNode.centerX, endNode.centerY)
    }

    /**
     * Drags from start element to end element using JS-based interaction.
     *
     * @throws ElementNotFoundException if start or end refid is not found in the cache
     */
    suspend fun jsDrag(startRefid: String, endRefid: String) {
        val startNode = nodeCache[startRefid] ?: throw ElementNotFoundException(startRefid)
        val endNode = nodeCache[endRefid] ?: throw ElementNotFoundException(endRefid)
        performDragByJs(webView, startNode.selector, endNode.selector)
    }

    suspend fun clickByCoordinates(x: Int, y: Int): OperationResult {
        performClickByCoordinates(webView, x, y)
        delay(200)
        return verifyClickAt(x, y, null)
    }

    suspend fun hoverByCoordinates(x: Int, y: Int) {
        performHoverByCoordinates(webView, x, y)
    }

    suspend fun scrollByCoordinates(x: Int, y: Int, deltaX: Int, deltaY: Int): OperationResult {
        // Read scroll position: walk up from element at coords to find scroll container
        val readScrollJs = """
            (function() {
                var vx = $x - window.scrollX, vy = $y - window.scrollY;
                var el = document.elementFromPoint(vx, vy);
                while (el) {
                    if (el.scrollHeight > el.clientHeight) return String(el.scrollTop);
                    el = el.parentElement;
                }
                return String(window.scrollY);
            })()
        """.trimIndent()
        val scrollBefore = evaluateJavascript(readScrollJs).trim().toDoubleOrNull() ?: 0.0
        performScrollByCoordinates(webView, x, y, deltaX, deltaY)
        delay(300)
        val scrollAfter = evaluateJavascript(readScrollJs).trim().toDoubleOrNull() ?: 0.0
        return if (scrollAfter != scrollBefore) {
            OperationResult.Success("scroll", detail = "scroll: $scrollBefore → $scrollAfter")
        } else {
            OperationResult.Failure("scroll", "scroll position unchanged ($scrollBefore)")
        }
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
    suspend fun snapshot(clean: Boolean = false): String {
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

    /**
     * 文件对话框请求回调。
     * 直接代理到底层 [KBWebView.onFileDialogRequest]。
     *
     * JVM Desktop: 设置后文件选择交由调用方处理；不设置时静默取消。
     * Android/iOS: 空实现，文件上传走平台原生流程。
     */
    var onFileDialog: ((request: KBFileDialogRequest, callback: KBFileDialogCallback) -> Unit)?
        get() = webView.onFileDialogRequest
        set(value) { webView.onFileDialogRequest = value }

    /**
     * 一步完成文件上传。
     *
     * 适用于 `<input type="file">` 元素：refid 指向 input 元素本身（即使隐藏也能上传）。
     *
     * JVM Desktop: 通过 CDP DOM.setFileInputFiles 直接设置文件到 input 元素，
     * 不需要弹文件对话框，不需要用户手势，不依赖元素可见性。
     *
     * Android/iOS: 不支持此方法（移动端走平台原生文件对话框），会抛 UnsupportedOperationException。
     *
     * @param refid input[type=file] 元素的 refid
     * @param filePaths 要上传的文件绝对路径列表
     * @throws ElementNotFoundException refid 不在缓存中
     * @throws UnsupportedOperationException Android/iOS 平台不支持
     */
    suspend fun uploadFile(refid: String, filePaths: List<String>) {
        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        performSetFiles(webView, node.selector, filePaths)
    }

    /**
     * 一步完成文件上传（CSS selector 版本）。
     *
     * 直接使用 CSS 选择器定位 input[type=file] 元素，不依赖 AX tree 缓存。
     * 适用于被隐藏（display:none）而不在 AX tree 中的 input 元素。
     *
     * JVM Desktop: 通过 CDP DOM.setFileInputFiles 直接设置文件。
     * Android/iOS: 不支持，会抛 UnsupportedOperationException。
     *
     * @param selector CSS 选择器，例如 "#fileInput" 或 "input[type=file]"
     * @param filePaths 要上传的文件绝对路径列表
     */
    suspend fun uploadFileBySelector(selector: String, filePaths: List<String>) {
        performSetFiles(webView, selector, filePaths)
    }

    fun close() {
        webView.destroy()
    }

    // ===== Operation Verification Helpers =====

    /**
     * 验证坐标点击是否命中目标元素。
     * 使用只读 JS API（elementFromPoint），零 anti-bot 检测风险。
     *
     * @param docX 文档 X 坐标
     * @param docY 文档 Y 坐标
     * @param targetNode 目标 AxNode（可选，提供更精确的匹配）
     */
    private suspend fun verifyClickAt(docX: Int, docY: Int, targetNode: AxNode?): OperationResult {
        val targetId = targetNode?.id ?: ""
        val targetTag = targetNode?.tagName ?: ""

        val js = """
            (function() {
                var scrollX = window.scrollX, scrollY = window.scrollY;
                var vx = $docX - scrollX, vy = $docY - scrollY;
                var el = document.elementFromPoint(vx, vy);
                while (el && el.shadowRoot) {
                    el = el.shadowRoot.elementFromPoint(vx, vy) || el;
                }
                if (!el) return 'NO_ELEMENT';
                var tag = el.tagName.toLowerCase();
                var id = el.id || '';
                var matched = false;
                var cur = el;
                for (var i = 0; i < 8 && cur; i++) {
                    ${if (targetId.isNotEmpty()) "if (cur.id === '$targetId') { matched = true; break; }" else ""}
                    ${if (targetTag.isNotEmpty()) "if (cur.tagName.toLowerCase() === '$targetTag') { matched = true; break; }" else ""}
                    cur = cur.parentElement;
                }
                return JSON.stringify({tag: tag, id: id, matched: matched});
            })()
        """.trimIndent()

        val result = evaluateJavascript(js).trim()
        if (result == "NO_ELEMENT") {
            return OperationResult.Failure("click", "no element at viewport coords")
        }

        return try {
            val jsonObj = kotlinx.serialization.json.Json.parseToJsonElement(result)
                .let { it as kotlinx.serialization.json.JsonObject }
            val matched = jsonObj["matched"]?.let {
                it as kotlinx.serialization.json.JsonPrimitive
            }?.content?.toBoolean() ?: false
            val elId = jsonObj["id"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: ""
            val elTag = jsonObj["tag"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: ""

            if (matched) {
                val detail = if (elId.isNotEmpty()) "hit #$elId" else "hit <$elTag>"
                OperationResult.Success("click", detail = detail)
            } else if (targetNode == null) {
                // No target info — can't verify identity, just confirm element exists
                val detail = if (elId.isNotEmpty()) "hit #$elId" else "hit <$elTag>"
                OperationResult.Success("click", verified = false, detail = detail)
            } else {
                val hitDesc = if (elId.isNotEmpty()) "#$elId" else "<$elTag>"
                val expectedDesc = if (targetId.isNotEmpty()) "#$targetId"
                    else if (targetTag.isNotEmpty()) "<$targetTag>" else "($docX,$docY)"
                OperationResult.Failure("click",
                    "hit $hitDesc, expected $expectedDesc (occluded?)")
            }
        } catch (e: Exception) {
            OperationResult.Success("click", verified = false)
        }
    }
}
