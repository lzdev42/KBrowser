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

internal actual fun performGlobalShutdown() {}
