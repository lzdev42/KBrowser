package xyz.kbrowser.webview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.delay
import kotlin.random.Random
import xyz.kbrowser.webview.debug.KBDebug

class KBPage(val webView: KBWebView) {
    val uuid: String = Random.nextLong().toString()
    val debug: KBDebug get() = webView.debug

    /**
     * Cache of node coordinates, refreshed on [getRawAxTree].
     *
     * @Volatile keeps reads immediately visible to all threads without locking, so read-only
     * operations such as click never block while getRawAxTree holds the write lock.
     * Writes are serialized by nodeCacheWriteLock to prevent data races.
     */
    private val nodeCacheWriteLock = Mutex()
    @Volatile private var nodeCache: Map<String, AxNode> = emptyMap()

    private suspend fun updateNodeCache(newCache: Map<String, AxNode>) {
        nodeCacheWriteLock.withLock {
            nodeCache = newCache
        }
    }

    private fun findPopupForNode(node: AxNode): AxNode? {
        val cache = nodeCache
        val popups = cache.values.filter { it.role == "dialog" || it.role == "alertdialog" }
        if (popups.isEmpty()) return null

        val refidToNode = cache

        for (popup in popups) {
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque<String>()
            for (cid in popup.childIds) {
                queue.add(cid)
            }
            while (queue.isNotEmpty()) {
                val refid = queue.removeFirst()
                if (refid in visited) continue
                visited.add(refid)
                if (refid == node.refid) return popup
                val child = refidToNode[refid] ?: continue
                for (cid in child.childIds) {
                    if (cid !in visited) queue.add(cid)
                }
            }
        }
        return null
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

    /**
     * Reloads the current page and suspends until it has finished loading.
     *
     * @throws Exception when the reload fails
     */
    suspend fun reload() {
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
                            continuation.resumeWith(Result.failure(Exception("Reload failed: ${error.description} (Code: ${error.errorCode})")))
                        }
                    }
                }

                continuation.invokeOnCancellation { webView.setWebViewClient(null) }
                webView.setWebViewClient(client)
                webView.reload()
            }
        }
    }

    /**
     * Scrolls the element with [refid] into the viewport (centered vertically),
     * using the DOM scrollIntoView API.
     *
     * Requires a prior [snapshot] to populate the node cache. Returns an
     * [OperationResult] whose detail carries the post-scroll bounding rect
     * (CSS viewport pixels), so the caller can verify visibility without a
     * new snapshot.
     *
     * @throws ElementNotFoundException if [refid] is not in the cache
     */
    suspend fun scrollIntoView(refid: String): OperationResult {
        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        val escaped = node.selector.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            (function(){
                var e = document.querySelector('$escaped');
                if (!e) return JSON.stringify({ok:false});
                e.scrollIntoView({block:'center', inline:'nearest', behavior:'instant'});
                var r = e.getBoundingClientRect();
                return JSON.stringify({ok:true, top:Math.round(r.top), left:Math.round(r.left),
                    width:Math.round(r.width), height:Math.round(r.height),
                    vw:window.innerWidth, vh:window.innerHeight});
            })()
        """.trimIndent()
        val result = evaluateJavascript(js).trim()
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(result) as kotlinx.serialization.json.JsonObject
            val ok = (obj["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBoolean() ?: false
            fun int(key: String) = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
            when {
                !ok -> OperationResult.Failure("scroll_into_view", "element not found by selector")
                int("top") < int("vh") && int("top") + int("height") > 0 &&
                    int("left") < int("vw") && int("left") + int("width") > 0 ->
                    OperationResult.Success(
                        "scroll_into_view",
                        detail = "rect top=${int("top")} left=${int("left")} ${int("width")}x${int("height")} (viewport ${int("vw")}x${int("vh")})"
                    )
                else -> OperationResult.Failure(
                    "scroll_into_view",
                    "still out of viewport after scroll (top=${int("top")}, vh=${int("vh")})"
                )
            }
        } catch (e: Exception) {
            OperationResult.Success("scroll_into_view", verified = false, detail = "scroll sent (verification failed: ${e.message})")
        }
    }

    /**
     * Waits for a condition, suspend-style. Throws [IllegalStateException] on timeout.
     *
     * - [text]       : wait until page body contains this text
     * - [textGone]   : wait until page body no longer contains this text
     * - [urlPattern] : wait until current URL contains this fragment
     *
     * At least one condition must be provided; conditions are AND-combined.
     * [timeoutMs] is capped at 60s; polls every 200ms.
     */
    suspend fun waitFor(
        text: String? = null,
        textGone: String? = null,
        urlPattern: String? = null,
        timeoutMs: Long = 10_000
    ) {
        require(text != null || textGone != null || urlPattern != null) {
            "waitFor: provide at least one of text / textGone / urlPattern"
        }
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(100, 60_000)
        val esc = { s: String -> s.replace("\\", "\\\\").replace("'", "\\'") }
        while (true) {
            val bodyOk = when {
                text != null -> evaluateJavascript(
                    "(function(){var b=document.body;return b&&b.innerText.indexOf('${esc(text)}')>=0})()"
                ).trim() == "true"
                else -> true
            }
            val goneOk = textGone == null || evaluateJavascript(
                "(function(){var b=document.body;return !(b&&b.innerText.indexOf('${esc(textGone)}')>=0)})()"
            ).trim() == "true"
            val urlOk = urlPattern == null || (webView.currentUrl.value ?: "").contains(urlPattern)
            if (bodyOk && goneOk && urlOk) return
            if (System.currentTimeMillis() >= deadline) {
                throw IllegalStateException("waitFor timeout after ${timeoutMs}ms (text=$text, textGone=$textGone, urlPattern=$urlPattern)")
            }
            delay(200)
        }
    }

    /**
     * Goes back in history and suspends until the previous page has finished loading.
     *
     * @throws IllegalStateException when there is no previous page in history,
     *         or the page did not finish loading within [timeoutMs]
     */
    suspend fun goBack(timeoutMs: Long = 15_000) {
        if (webView.canGoBack.value != true) throw IllegalStateException("no previous page in history")
        val timeout = timeoutMs.coerceIn(1000, 60_000)
        val deadline = System.currentTimeMillis() + timeout
        val urlBefore = webView.currentUrl.value
        var sawActivity = false
        withContext(Dispatchers.Main) { webView.goBack() }
        while (System.currentTimeMillis() < deadline) {
            val state = webView.loadingState.value
            if (state is LoadingState.Loading) sawActivity = true
            if (webView.currentUrl.value != urlBefore) sawActivity = true
            if (sawActivity && state is LoadingState.Finished) return
            delay(200)
        }
        throw IllegalStateException("goBack: page did not finish loading within ${timeout}ms")
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

    /**
     * Takes a screenshot of the page and returns PNG bytes.
     * Screenshot pixels align 1:1 with CSS coordinates (already DPR-scaled). When dimensions
     * are needed, use [webView.takeScreenshot] to get a [KBScreenshot].
     */
    suspend fun screenshot(): ByteArray? =
        webView.takeScreenshot()?.imageData

    private suspend fun getRawAxTree(): AxTreeData {
        // Prefer the native CDP route on JVM: no JS injection, not blocked by CSP.
        val nativeTree = fetchAxTreeNative(webView)
        val treeData = if (nativeTree != null) {
            nativeTree
        } else {
            // Fallback: JS injection route (Android / iOS).
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
        val popup = findPopupForNode(node)
        val popupSelector = popup?.selector
        val viewportCoords = performClickByCoordinates(webView, node.centerX, node.centerY, popupSelector)
        delay(200)
        return if (viewportCoords != null) {
            verifyClickAtViewport(viewportCoords.first, viewportCoords.second, node)
        } else {
            verifyClickAt(node.centerX, node.centerY, node)
        }
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
        val popup = findPopupForNode(node)
        performHoverByCoordinates(webView, node.centerX, node.centerY, popup?.selector)
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
        val viewportCoords = performClickByCoordinates(webView, x, y, null)
        delay(200)
        return if (viewportCoords != null) {
            verifyClickAtViewport(viewportCoords.first, viewportCoords.second, null)
        } else {
            verifyClickAt(x, y, null)
        }
    }

    suspend fun hoverByCoordinates(x: Int, y: Int) {
        performHoverByCoordinates(webView, x, y, null)
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

    /**
     * Locks/unlocks user interaction.
     * locked=true: overlays an AWT interception layer on the browser that blocks user
     * mouse/keyboard input and shows the mouse trail.
     * locked=false: removes the interception layer and restores user interaction.
     * Automation (CDP) is unaffected. JVM only; no-op on Android/iOS.
     */
    fun setInteractionLocked(locked: Boolean) {
        setInteractionLockedNative(webView, locked)
    }

    /**
     * Updates the mouse trail position (shows the automation cursor animation while interaction
     * is locked).
     * Coordinates are viewport coordinates (CSS pixels). JVM only.
     */
    fun updateMouseTrail(viewportX: Int, viewportY: Int) {
        updateMouseTrailNative(webView, viewportX, viewportY)
    }

    /**
     * Returns the current page state as a [SnapshotResult].
     *
     * [mode] controls the YAML serialization:
     * - [SnapshotMode.VIEWPORT]: viewport-only compact YAML for AI consumption.
     * - [SnapshotMode.CLEAN]: full-page compact YAML (all nodes, cleaned but no viewport filtering).
     *
     * The returned [SnapshotResult] always contains both the YAML string ([SnapshotResult.yaml])
     * and the raw [AxTreeData] ([SnapshotResult.rawTree]) from the same underlying fetch,
     * so refids are guaranteed consistent between the two.
     */
    suspend fun snapshot(mode: SnapshotMode = SnapshotMode.VIEWPORT): SnapshotResult {
        val rawTree = getRawAxTree()
        val yaml = rawTree.toYamlSnapshot(mode)
        return SnapshotResult(yaml, rawTree)
    }

    /**
     * Callback for new tab/window requests.
     * Fired when a page requests a new window via target="_blank", window.open(), etc.
     * Delegates directly to [KBWebView.onNewWindowRequest].
     *
     * Example:
     * ```kotlin
     * page.onNewPage = { url -> println("Opening new page: $url") }
     * ```
     */
    var onNewPage: ((url: String) -> Unit)?
        get() = webView.onNewWindowRequest
        set(value) { webView.onNewWindowRequest = value }

    /**
     * Callback for file dialog requests.
     * Delegates directly to [KBWebView.onFileDialogRequest].
     *
     * JVM Desktop: when set, file selection is handled by the caller; when unset, the request
     * is silently cancelled.
     * Android/iOS: no-op; file uploads go through the platform's native flow.
     */
    var onFileDialog: ((request: KBFileDialogRequest, callback: KBFileDialogCallback) -> Unit)?
        get() = webView.onFileDialogRequest
        set(value) { webView.onFileDialogRequest = value }

    /**
     * Uploads files in one step.
     *
     * For `<input type="file">` elements: [refid] points at the input element itself
     * (the upload works even when the element is hidden).
     *
     * JVM Desktop: sets files on the input element directly via CDP DOM.setFileInputFiles —
     * no file dialog, no user gesture, no visibility requirement.
     *
     * Android/iOS: not supported (mobile uses the platform's native file dialog); throws
     * UnsupportedOperationException.
     *
     * @param refid refid of the input[type=file] element
     * @param filePaths absolute paths of the files to upload
     * @throws ElementNotFoundException if [refid] is not in the cache
     * @throws UnsupportedOperationException on Android/iOS
     */
    suspend fun uploadFile(refid: String, filePaths: List<String>) {
        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        performSetFiles(webView, node.selector, filePaths)
    }

    /**
     * Uploads files in one step (CSS selector variant).
     *
     * Locates the input[type=file] element directly by CSS selector, without relying on the
     * AX tree cache. Useful for hidden (display:none) inputs that never appear in the AX tree.
     *
     * JVM Desktop: sets files directly via CDP DOM.setFileInputFiles.
     * Android/iOS: not supported; throws UnsupportedOperationException.
     *
     * @param selector CSS selector, e.g. "#fileInput" or "input[type=file]"
     * @param filePaths absolute paths of the files to upload
     */
    suspend fun uploadFileBySelector(selector: String, filePaths: List<String>) {
        performSetFiles(webView, selector, filePaths)
    }

    fun close() {
        webView.destroy()
    }

    private suspend fun verifyClickAtViewport(vx: Int, vy: Int, targetNode: AxNode?): OperationResult {
        val targetId = targetNode?.id ?: ""
        val targetTag = targetNode?.tagName ?: ""

        val cdpResult = verifyElementAtCdp(webView, vx, vy)
        val result = if (cdpResult != null && cdpResult != "NO_ELEMENT") {
            cdpResult.trim()
        } else if (cdpResult == "NO_ELEMENT") {
            "NO_ELEMENT"
        } else {
            val js = """
                (function() {
                    var el = document.elementFromPoint($vx, $vy);
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
            evaluateJavascript(js).trim()
        }

        if (result == "NO_ELEMENT") {
            return OperationResult.Failure("click", "no element at viewport coords")
        }

        return try {
            val jsonObj = kotlinx.serialization.json.Json.parseToJsonElement(result)
                .let { it as kotlinx.serialization.json.JsonObject }
            val elId = jsonObj["id"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: ""
            val elTag = jsonObj["tag"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: ""
            val jsMatched = jsonObj["matched"]?.let {
                it as kotlinx.serialization.json.JsonPrimitive
            }?.content?.toBoolean()
            val matched = jsMatched ?: ((elId.isNotEmpty() && elId == targetId) || (elTag.isNotEmpty() && elTag == targetTag))

            if (matched) {
                val detail = if (elId.isNotEmpty()) "hit #$elId" else "hit <$elTag>"
                OperationResult.Success("click", detail = detail)
            } else if (targetNode == null) {
                val detail = if (elId.isNotEmpty()) "hit #$elId" else "hit <$elTag>"
                OperationResult.Success("click", verified = false, detail = detail)
            } else {
                val hitDesc = if (elId.isNotEmpty()) "#$elId" else "<$elTag>"
                val expectedDesc = if (targetId.isNotEmpty()) "#$targetId"
                    else if (targetTag.isNotEmpty()) "<$targetTag>" else "($vx,$vy)"
                OperationResult.Failure("click",
                    "hit $hitDesc, expected $expectedDesc (occluded?)")
            }
        } catch (e: Exception) {
            OperationResult.Success("click", verified = false)
        }
    }

    /**
     * Verifies whether a coordinate click hit the target element.
     * Uses read-only JS APIs (elementFromPoint), so it carries zero anti-bot detection risk.
     *
     * @param docX document X coordinate
     * @param docY document Y coordinate
     * @param targetNode target AxNode (optional; enables more precise matching)
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
