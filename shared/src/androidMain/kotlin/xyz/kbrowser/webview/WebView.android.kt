package xyz.kbrowser.webview

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.ProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import xyz.kbrowser.webview.debug.KBDebug
import xyz.kbrowser.webview.debug.KBDebugNoop
import kotlin.coroutines.resume

class AndroidWebView(
    private val initialUrl: String?,
    val profile: KBProfile?,
    private val viewportWidth: Int? = null,
    private val viewportHeight: Int? = null
) : KBWebView {
    override val currentUrl = MutableStateFlow<String?>(initialUrl)
    override val currentTitle = MutableStateFlow<String?>(null)
    override val loadingState = MutableStateFlow<LoadingState>(LoadingState.Initializing)
    override val progress = MutableStateFlow(0f)
    override val canGoBack = MutableStateFlow(false)
    override val canGoForward = MutableStateFlow(false)

    private var webView: WebView? = null
    private var webViewClient: KBWebViewClient? = null
    private var webChromeClient: KBWebChromeClient? = null

    fun getOrCreateWebView(context: Context): WebView {
        var w = webView
        if (w == null) {
            w = WebView(context)
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            
            if (profile != null && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try {
                    val profileStore = ProfileStore.getInstance()
                    val p = profileStore.getOrCreateProfile(profile.profileId)
                    WebViewCompat.setProfile(w, p.name)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            w.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    currentUrl.value = url
                    loadingState.value = LoadingState.Loading
                    progress.value = 0.1f
                    url?.let { webViewClient?.onPageStarted(it) }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    currentUrl.value = url
                    loadingState.value = LoadingState.Finished
                    progress.value = 1.0f
                    canGoBack.value = view?.canGoBack() ?: false
                    canGoForward.value = view?.canGoForward() ?: false
                    url?.let { webViewClient?.onPageFinished(it) }
                }

                @Suppress("DEPRECATION")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    val errorDesc = description ?: "Unknown error"
                    val errorUrl = failingUrl ?: ""
                    loadingState.value = LoadingState.Error(errorCode, errorDesc, errorUrl)
                    webViewClient?.onReceivedError(Diagnostics(errorCode, errorDesc, errorUrl))
                }
            }

            w.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.value = newProgress / 100f
                    if (newProgress == 100) {
                        loadingState.value = LoadingState.Finished
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    currentTitle.value = title
                }

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    if (result == null) return false
                    webChromeClient?.onJsAlert(url ?: "", message ?: "", object : JsResultCallback {
                        override fun confirm() { result.confirm() }
                        override fun cancel() { result.cancel() }
                    })
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    if (result == null) return false
                    webChromeClient?.onJsConfirm(url ?: "", message ?: "", object : JsResultCallback {
                        override fun confirm() { result.confirm() }
                        override fun cancel() { result.cancel() }
                    })
                    return true
                }

                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
                    if (result == null) return false
                    webChromeClient?.onJsPrompt(url ?: "", message ?: "", defaultValue, object : JsPromptResultCallback {
                        override fun confirm(value: String?) {
                            if (value != null) result.confirm(value) else result.confirm()
                        }
                        override fun cancel() { result.cancel() }
                    })
                    return true
                }

                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    if (request == null) return
                    val mapped = request.resources.mapNotNull { res ->
                        when (res) {
                            android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> PermissionResource.VIDEO_CAPTURE
                            android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> PermissionResource.AUDIO_CAPTURE
                            android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> PermissionResource.PROTECTED_MEDIA_IDENTIFIER
                            else -> null
                        }
                    }
                    val kbRequest = object : PermissionRequest {
                        override val origin: String = ""
                        override val resources: List<PermissionResource> = mapped
                        override fun grant() {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                request.grant(request.resources)
                            }
                        }
                        override fun deny() {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                request.deny()
                            }
                        }
                    }
                    webChromeClient?.onPermissionRequest(kbRequest)
                }
            }

            val displayMetrics = context.resources.displayMetrics
            val vpW = viewportWidth ?: displayMetrics.widthPixels
            val vpH = viewportHeight ?: displayMetrics.heightPixels
            w.layout(0, 0, vpW, vpH)

            webView = w
            if (initialUrl != null) {
                w.loadUrl(initialUrl)
            }
        }
        return w
    }

    override fun loadUrl(url: String) {
        val w = getOrCreateWebView(AndroidContextHolder.context)
        w.loadUrl(url)
    }

    override fun loadHtml(html: String) {
        val w = getOrCreateWebView(AndroidContextHolder.context)
        w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    override fun reload() {
        webView?.reload()
    }

    override fun stopLoading() {
        webView?.stopLoading()
    }

    override fun goBack() {
        webView?.goBack()
    }

    override fun goForward() {
        webView?.goForward()
    }

    override fun evaluateJavascript(script: String, callback: ((String) -> Unit)?) {
        val w = getOrCreateWebView(AndroidContextHolder.context)
        w.evaluateJavascript(script) { result ->
            callback?.invoke(result)
        }
    }

    override fun registerJsCallback(name: String, callback: (String) -> Unit) {
        val w = getOrCreateWebView(AndroidContextHolder.context)
        w.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun postMessage(message: String) {
                callback(message)
            }
        }, name)
    }

    override fun unregisterJsCallback(name: String) {
        webView?.removeJavascriptInterface(name)
    }

    /**
     * Android 实现：通过 JavascriptInterface + evaluateJavascript 模拟 Promise。
     * 注入一个 __kb_handler_[name] 的 JavascriptInterface，JS 端包装成 Promise。
     * handler 在 JavascriptInterface 线程执行，结果通过 evaluateJavascript 传回 resolve。
     */
    private val jsHandlerCallbacks = mutableMapOf<String, (String) -> String>()

    override fun registerJsHandler(name: String, handler: (String) -> String) {
        val w = getOrCreateWebView(AndroidContextHolder.context)
        jsHandlerCallbacks[name] = handler
        val bridgeName = "__kb_handler_$name"
        w.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun call(callId: String, message: String): String {
                return try {
                    handler(message)
                } catch (e: Exception) {
                    "__KB_ERROR__:${e.message}"
                }
            }
        }, bridgeName)
        // 注入 Promise 包装
        val script = """
            window.$name = function(arg) {
                return new Promise(function(resolve, reject) {
                    try {
                        var result = window.$bridgeName.call('', typeof arg === 'string' ? arg : JSON.stringify(arg));
                        if (result && result.indexOf('__KB_ERROR__:') === 0) {
                            reject(new Error(result.substring(13)));
                        } else {
                            resolve(result);
                        }
                    } catch(e) {
                        reject(e);
                    }
                });
            };
        """.trimIndent()
        w.evaluateJavascript(script, null)
    }

    override fun unregisterJsHandler(name: String) {
        jsHandlerCallbacks.remove(name)
        webView?.removeJavascriptInterface("__kb_handler_$name")
        webView?.evaluateJavascript("delete window.$name;", null)
    }

    override fun clearCacheAndCookies() {
        val w = getOrCreateWebView(AndroidContextHolder.context)
        w.clearCache(true)
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
    }

    override fun setWebViewClient(client: KBWebViewClient?) {
        this.webViewClient = client
    }

    override fun setWebChromeClient(client: KBWebChromeClient?) {
        this.webChromeClient = client
    }

    override var onNewWindowRequest: ((url: String) -> Unit)? = null

    override var onFileDialogRequest: ((request: KBFileDialogRequest, callback: KBFileDialogCallback) -> Unit)? = null

    override val debug: KBDebug = KBDebugNoop

    override fun destroy() {
        webView?.destroy()
        webView = null
    }

    override suspend fun takeScreenshot(): KBScreenshot? {
        val w = webView ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val width = w.width
            val height = w.height
            if (width <= 0 || height <= 0) return@withContext null

            val density = w.context.resources.displayMetrics.density
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            w.draw(canvas)

            val targetBitmap = if (density > 1.0f) {
                val targetW = (width / density).toInt()
                val targetH = (height / density).toInt()
                if (targetW > 0 && targetH > 0) {
                    val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                    if (scaled != bitmap) {
                        bitmap.recycle()
                    }
                    scaled
                } else {
                    bitmap
                }
            } else {
                bitmap
            }

            val baos = java.io.ByteArrayOutputStream()
            try {
                targetBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                val imageData = baos.toByteArray()
                KBScreenshot(
                    imageData = imageData,
                    width = targetBitmap.width,
                    height = targetBitmap.height
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                if (targetBitmap != bitmap && !targetBitmap.isRecycled) {
                    targetBitmap.recycle()
                }
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }
}

internal actual fun setInteractionLockedNative(webView: KBWebView, locked: Boolean) { /* Android: not implemented */ }
internal actual fun updateMouseTrailNative(webView: KBWebView, viewportX: Int, viewportY: Int) { /* Android: not implemented */ }

@Composable
actual fun KBWebView(
    webView: KBWebView,
    modifier: Modifier
) {
    val androidWebView = webView as? AndroidWebView ?: return
    AndroidView(
        factory = { ctx ->
            androidWebView.getOrCreateWebView(ctx)
        },
        modifier = modifier
    )
}

@Composable
actual fun rememberKBWebView(
    initialUrl: String?,
    profile: KBProfile?
): KBWebView {
    val context = androidx.compose.ui.platform.LocalContext.current
    val webView = remember(initialUrl, profile) {
        AndroidWebView(initialUrl, profile, viewportWidth = null, viewportHeight = null).apply {
            getOrCreateWebView(context)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.destroy()
        }
    }
    return webView
}

internal actual fun createHeadlessWebView(
    initialUrl: String?,
    profile: KBProfile?,
    viewportWidth: Int?,
    viewportHeight: Int?,
    headless: Boolean
): KBWebView {
    return AndroidWebView(initialUrl, profile, viewportWidth, viewportHeight)
}

internal actual suspend fun performClickByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    popupSelector: String?
): Pair<Int, Int>? {
    if (webView is AndroidWebView) {
        val w = webView.getOrCreateWebView(AndroidContextHolder.context)
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0
        )
        w.dispatchTouchEvent(downEvent)
        
        val upTime = SystemClock.uptimeMillis() + 100
        val upEvent = MotionEvent.obtain(
            downTime, upTime, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0
        )
        w.dispatchTouchEvent(upEvent)
    }
    return null
}

internal actual suspend fun performHoverByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    popupSelector: String?
) {
    if (webView is AndroidWebView) {
        val w = webView.getOrCreateWebView(AndroidContextHolder.context)
        val eventTime = SystemClock.uptimeMillis()
        val hoverEvent = MotionEvent.obtain(
            eventTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, x.toFloat(), y.toFloat(), 0
        )
        w.dispatchTouchEvent(hoverEvent)
    }
}

internal actual suspend fun verifyElementAtCdp(
    webView: KBWebView,
    vx: Int,
    vy: Int
): String? {
    return null
}

internal actual suspend fun performScrollByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    deltaX: Int,
    deltaY: Int
) {
    if (webView is AndroidWebView) {
        val w = webView.getOrCreateWebView(AndroidContextHolder.context)
        w.scrollBy(deltaX, deltaY)
    }
}

internal actual suspend fun performDragByCoordinates(
    webView: KBWebView,
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int
) {
    if (webView is AndroidWebView) {
        val w = webView.getOrCreateWebView(AndroidContextHolder.context)
        val downTime = SystemClock.uptimeMillis()
        
        val downEvent = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, startX.toFloat(), startY.toFloat(), 0
        )
        w.dispatchTouchEvent(downEvent)
        
        val moveTime = downTime + 200
        val moveEvent = MotionEvent.obtain(
            downTime, moveTime, MotionEvent.ACTION_MOVE, endX.toFloat(), endY.toFloat(), 0
        )
        w.dispatchTouchEvent(moveEvent)
        
        val upTime = downTime + 300
        val upEvent = MotionEvent.obtain(
            downTime, upTime, MotionEvent.ACTION_UP, endX.toFloat(), endY.toFloat(), 0
        )
        w.dispatchTouchEvent(upEvent)
    }
}

internal actual suspend fun performKeyPress(
    webView: KBWebView,
    key: KeyboardKey
) {
    if (webView is AndroidWebView) {
        val w = webView.getOrCreateWebView(AndroidContextHolder.context)
        val downTime = SystemClock.uptimeMillis()
        val keyCode = keyToAndroidKeyCode(key)
        val event = android.view.KeyEvent(downTime, downTime,
            android.view.KeyEvent.ACTION_DOWN, keyCode, 0)
        w.dispatchKeyEvent(event)
        val upEvent = android.view.KeyEvent(downTime, downTime + 50,
            android.view.KeyEvent.ACTION_UP, keyCode, 0)
        w.dispatchKeyEvent(upEvent)
    }
}

internal actual suspend fun performKeyCombination(
    webView: KBWebView,
    modifier: KeyboardKey,
    key: KeyboardKey
) {
    if (webView is AndroidWebView) {
        val w = webView.getOrCreateWebView(AndroidContextHolder.context)
        val downTime = SystemClock.uptimeMillis()
        val modKeyCode = keyToAndroidKeyCode(modifier)
        val keyCode = keyToAndroidKeyCode(key)
        val modMetaState = keyToAndroidMetaState(modifier)

        // Modifier DOWN
        val modDown = android.view.KeyEvent(downTime, downTime,
            android.view.KeyEvent.ACTION_DOWN, modKeyCode, modMetaState)
        w.dispatchKeyEvent(modDown)

        // Key DOWN (with modifier meta state)
        val keyDown = android.view.KeyEvent(downTime, downTime + 10,
            android.view.KeyEvent.ACTION_DOWN, keyCode, modMetaState)
        w.dispatchKeyEvent(keyDown)

        // Key UP (with modifier meta state)
        val keyUp = android.view.KeyEvent(downTime, downTime + 60,
            android.view.KeyEvent.ACTION_UP, keyCode, modMetaState)
        w.dispatchKeyEvent(keyUp)

        // Modifier UP
        val modUp = android.view.KeyEvent(downTime, downTime + 70,
            android.view.KeyEvent.ACTION_UP, modKeyCode, 0)
        w.dispatchKeyEvent(modUp)
    }
}

internal actual suspend fun performTypeChar(
    webView: KBWebView,
    char: Char
) {
    if (webView is AndroidWebView) {
        val w = webView.getOrCreateWebView(AndroidContextHolder.context)
        val downTime = SystemClock.uptimeMillis()
        val keyCode = charToAndroidKeyCode(char)

        val metaState = if (char.isUpperCase()) android.view.KeyEvent.META_SHIFT_ON else 0

        // ACTION_DOWN
        val downEvent = android.view.KeyEvent(downTime, downTime,
            android.view.KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
        w.dispatchKeyEvent(downEvent)

        // ACTION_UP
        val upEvent = android.view.KeyEvent(downTime, downTime + 50,
            android.view.KeyEvent.ACTION_UP, keyCode, 0, metaState)
        w.dispatchKeyEvent(upEvent)
    }
}

private fun keyToAndroidKeyCode(key: KeyboardKey): Int = when (key) {
    KeyboardKey.ENTER -> android.view.KeyEvent.KEYCODE_ENTER
    KeyboardKey.TAB -> android.view.KeyEvent.KEYCODE_TAB
    KeyboardKey.ESCAPE -> android.view.KeyEvent.KEYCODE_ESCAPE
    KeyboardKey.BACKSPACE -> android.view.KeyEvent.KEYCODE_DEL
    KeyboardKey.DELETE -> android.view.KeyEvent.KEYCODE_FORWARD_DEL
    KeyboardKey.ARROW_UP -> android.view.KeyEvent.KEYCODE_DPAD_UP
    KeyboardKey.ARROW_DOWN -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
    KeyboardKey.ARROW_LEFT -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
    KeyboardKey.ARROW_RIGHT -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
    KeyboardKey.SHIFT -> android.view.KeyEvent.KEYCODE_SHIFT_LEFT
    KeyboardKey.CONTROL -> android.view.KeyEvent.KEYCODE_CTRL_LEFT
    KeyboardKey.ALT -> android.view.KeyEvent.KEYCODE_ALT_LEFT
    KeyboardKey.META -> android.view.KeyEvent.KEYCODE_META_LEFT
    KeyboardKey.SPACE -> android.view.KeyEvent.KEYCODE_SPACE
    KeyboardKey.HOME -> android.view.KeyEvent.KEYCODE_MOVE_HOME
    KeyboardKey.END -> android.view.KeyEvent.KEYCODE_MOVE_END
    KeyboardKey.PAGE_UP -> android.view.KeyEvent.KEYCODE_PAGE_UP
    KeyboardKey.PAGE_DOWN -> android.view.KeyEvent.KEYCODE_PAGE_DOWN
    KeyboardKey.INSERT -> android.view.KeyEvent.KEYCODE_INSERT
    KeyboardKey.F1 -> android.view.KeyEvent.KEYCODE_F1
    KeyboardKey.F2 -> android.view.KeyEvent.KEYCODE_F2
    KeyboardKey.F3 -> android.view.KeyEvent.KEYCODE_F3
    KeyboardKey.F4 -> android.view.KeyEvent.KEYCODE_F4
    KeyboardKey.F5 -> android.view.KeyEvent.KEYCODE_F5
    KeyboardKey.F6 -> android.view.KeyEvent.KEYCODE_F6
    KeyboardKey.F7 -> android.view.KeyEvent.KEYCODE_F7
    KeyboardKey.F8 -> android.view.KeyEvent.KEYCODE_F8
    KeyboardKey.F9 -> android.view.KeyEvent.KEYCODE_F9
    KeyboardKey.F10 -> android.view.KeyEvent.KEYCODE_F10
    KeyboardKey.F11 -> android.view.KeyEvent.KEYCODE_F11
    KeyboardKey.F12 -> android.view.KeyEvent.KEYCODE_F12
    KeyboardKey.A -> android.view.KeyEvent.KEYCODE_A
    KeyboardKey.C -> android.view.KeyEvent.KEYCODE_C
    KeyboardKey.V -> android.view.KeyEvent.KEYCODE_V
    KeyboardKey.X -> android.view.KeyEvent.KEYCODE_X
    KeyboardKey.S -> android.view.KeyEvent.KEYCODE_S
    KeyboardKey.Z -> android.view.KeyEvent.KEYCODE_Z
}

private fun keyToAndroidMetaState(key: KeyboardKey): Int = when (key) {
    KeyboardKey.SHIFT -> android.view.KeyEvent.META_SHIFT_ON
    KeyboardKey.CONTROL -> android.view.KeyEvent.META_CTRL_ON
    KeyboardKey.ALT -> android.view.KeyEvent.META_ALT_ON
    KeyboardKey.META -> android.view.KeyEvent.META_META_ON
    else -> 0
}

private fun charToAndroidKeyCode(char: Char): Int = when (char) {
    '\n' -> android.view.KeyEvent.KEYCODE_ENTER
    '\t' -> android.view.KeyEvent.KEYCODE_TAB
    '\b' -> android.view.KeyEvent.KEYCODE_DEL
    ' ' -> android.view.KeyEvent.KEYCODE_SPACE
    in 'a'..'z' -> android.view.KeyEvent.KEYCODE_A + (char - 'a')
    in 'A'..'Z' -> android.view.KeyEvent.KEYCODE_A + (char.uppercaseChar() - 'A')
    in '0'..'9' -> android.view.KeyEvent.KEYCODE_0 + (char - '0')
    else -> android.view.KeyEvent.KEYCODE_UNKNOWN
}

internal actual fun performGlobalShutdown() {}

internal actual suspend fun fetchAxTreeNative(webView: KBWebView): AxTreeData? = null

internal actual suspend fun findElementsNative(
    webView: KBWebView,
    selector: String,
    selectorType: KBSelectorType,
    name: String?,
    exact: Boolean
): List<LocateResult>? = null  // Android uses JS fallback

private suspend fun KBWebView.evaluateJsSuspend(script: String): String {
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            evaluateJavascript(script) { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
    }
}

internal actual suspend fun performClickByJs(
    webView: KBWebView,
    selector: String
) {
    val escaped = selector.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "")
    val js = """
        (function() {
            var el = document.querySelector("$escaped");
            if (el) {
                el.click();
                return "ok";
            }
            return "not_found";
        })()
    """.trimIndent()
    webView.evaluateJsSuspend(js)
}

internal actual suspend fun performHoverByJs(
    webView: KBWebView,
    selector: String
) {
    val escaped = selector.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "")
    val js = """
        (function() {
            var el = document.querySelector("$escaped");
            if (el) {
                var events = ["mouseover", "mouseenter", "mousemove"];
                events.forEach(function(name) {
                    var ev = new MouseEvent(name, { bubbles: true, cancelable: true, view: window });
                    el.dispatchEvent(ev);
                });
                return "ok";
            }
            return "not_found";
        })()
    """.trimIndent()
    webView.evaluateJsSuspend(js)
}

internal actual suspend fun performScrollByJs(
    webView: KBWebView,
    selector: String,
    deltaX: Int,
    deltaY: Int
) {
    val escaped = selector.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "")
    val js = """
        (function() {
            var el = document.querySelector("$escaped");
            if (el) {
                el.scrollBy($deltaX, $deltaY);
                return "ok";
            }
            return "not_found";
        })()
    """.trimIndent()
    webView.evaluateJsSuspend(js)
}

internal actual suspend fun performDragByJs(
    webView: KBWebView,
    startSelector: String,
    endSelector: String
) {
    val escapedStart = startSelector.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "")
    val escapedEnd = endSelector.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "")
    val js = """
        (function() {
            var startEl = document.querySelector("$escapedStart");
            var endEl = document.querySelector("$escapedEnd");
            if (startEl && endEl) {
                var startRect = startEl.getBoundingClientRect();
                var endRect = endEl.getBoundingClientRect();
                var startX = startRect.left + startRect.width / 2;
                var startY = startRect.top + startRect.height / 2;
                var endX = endRect.left + endRect.width / 2;
                var endY = endRect.top + endRect.height / 2;

                var down = new MouseEvent("mousedown", { bubbles: true, cancelable: true, clientX: startX, clientY: startY });
                startEl.dispatchEvent(down);

                var steps = 5;
                for (var i = 1; i <= steps; i++) {
                    var ratio = i / steps;
                    var currX = startX + (endX - startX) * ratio;
                    var currY = startY + (endY - startY) * ratio;
                    var move = new MouseEvent("mousemove", { bubbles: true, cancelable: true, clientX: currX, clientY: currY });
                    document.dispatchEvent(move);
                }

                var up = new MouseEvent("mouseup", { bubbles: true, cancelable: true, clientX: endX, clientY: endY });
                endEl.dispatchEvent(up);
                return "ok";
            }
            return "not_found";
        })()
    """.trimIndent()
    webView.evaluateJsSuspend(js)
}

internal actual suspend fun performFocusByJs(
    webView: KBWebView,
    selector: String
) {
    val escaped = selector.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "")
    val js = """
        (function() {
            var el = document.querySelector("$escaped");
            if (el) {
                el.focus();
                return "ok";
            }
            return "not_found";
        })()
    """.trimIndent()
    webView.evaluateJsSuspend(js)
}

internal actual suspend fun performSetFiles(
    webView: KBWebView,
    selector: String,
    filePaths: List<String>
) {
    throw UnsupportedOperationException("File upload via CDP is not supported on Android. Use native file dialog instead.")
}
