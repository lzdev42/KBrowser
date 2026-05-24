package xyz.kbrowser.webview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

class KBPage internal constructor(val webView: KBWebView) {
    val uuid: String = Random.nextLong().toString()
    
    val currentUrl: StateFlow<String?> get() = webView.currentUrl
    val title: StateFlow<String?> get() = webView.currentTitle

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
                            continuation.resumeWith(Result.failure(Exception("加载页面失败: ${error.description} (Code: ${error.errorCode})")))
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
        return jsonParser.decodeFromString<AxTreeData>(json)
    }

    suspend fun clickByRefId(refid: String) {
        val js = """
            (function() {
                var el = window.__kb_element_map ? window.__kb_element_map.get("$refid") : null;
                if (!el) return 'NOT_FOUND';
                el.click();
                return 'OK';
            })()
        """.trimIndent()
        val result = evaluateJavascript(js)
        if (result.trim('"') == "NOT_FOUND") {
            throw ElementNotFoundException(refid)
        }
    }

    suspend fun hoverByRefId(refid: String) {
        val js = """
            (function() {
                var el = window.__kb_element_map ? window.__kb_element_map.get("$refid") : null;
                if (!el) return 'NOT_FOUND';
                var ev1 = new MouseEvent('mouseover', { bubbles: true });
                var ev2 = new MouseEvent('mouseenter', { bubbles: true });
                el.dispatchEvent(ev1);
                el.dispatchEvent(ev2);
                return 'OK';
            })()
        """.trimIndent()
        val result = evaluateJavascript(js)
        if (result.trim('"') == "NOT_FOUND") {
            throw ElementNotFoundException(refid)
        }
    }

    suspend fun scrollByRefId(refid: String, deltaX: Int, deltaY: Int) {
        val js = """
            (function() {
                var el = window.__kb_element_map ? window.__kb_element_map.get("$refid") : null;
                if (!el) return 'NOT_FOUND';
                el.scrollBy($deltaX, $deltaY);
                return 'OK';
            })()
        """.trimIndent()
        val result = evaluateJavascript(js)
        if (result.trim('"') == "NOT_FOUND") {
            throw ElementNotFoundException(refid)
        }
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

    fun close() {
        webView.destroy()
    }
}
