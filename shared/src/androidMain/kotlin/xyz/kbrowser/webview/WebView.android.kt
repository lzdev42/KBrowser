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

class AndroidWebView(
    private val initialUrl: String?,
    val profile: KBProfile?
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
            }

            w.layout(0, 0, 1280, 800)

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

    override fun destroy() {
        webView?.destroy()
        webView = null
    }
}

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
        AndroidWebView(initialUrl, profile).apply {
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
    isOsr: Boolean
): KBWebView {
    return AndroidWebView(initialUrl, profile)
}

internal actual suspend fun performClickByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int
) {
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
}

internal actual suspend fun performHoverByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int
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
