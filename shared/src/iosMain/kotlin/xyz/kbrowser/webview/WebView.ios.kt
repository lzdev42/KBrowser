package xyz.kbrowser.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.Foundation.*
import platform.darwin.NSObject
import kotlinx.cinterop.readValue
import kotlinx.cinterop.ExperimentalForeignApi

class IosWebView(
    private val initialUrl: String?,
    val profile: KBProfile?
) : KBWebView {
    override val currentUrl = MutableStateFlow<String?>(initialUrl)
    override val currentTitle = MutableStateFlow<String?>(null)
    override val loadingState = MutableStateFlow<LoadingState>(LoadingState.Initializing)
    override val progress = MutableStateFlow(0f)
    override val canGoBack = MutableStateFlow(false)
    override val canGoForward = MutableStateFlow(false)

    private var webView: WKWebView? = null
    private var webViewClient: KBWebViewClient? = null
    private var webChromeClient: KBWebChromeClient? = null

    @OptIn(ExperimentalForeignApi::class)
    fun getOrCreateWebView(): WKWebView {
        var w = webView
        if (w == null) {
            val store = if (profile != null) {
                try {
                    WKWebsiteDataStore.dataStoreForIdentifier(NSUUID(uUIDString = profile.profileId)!!)
                } catch (e: Throwable) {
                    WKWebsiteDataStore.nonPersistentDataStore()
                }
            } else {
                WKWebsiteDataStore.defaultDataStore()
            }
            
            val config = WKWebViewConfiguration().apply {
                websiteDataStore = store
            }
            
            // 注册 pageFinished 监听，用于更新页面状态，替代冲突的 navigationDelegate
            val handler = object : NSObject(), platform.WebKit.WKScriptMessageHandlerProtocol {
                override fun userContentController(
                    userContentController: platform.WebKit.WKUserContentController,
                    didReceiveScriptMessage: platform.WebKit.WKScriptMessage
                ) {
                    if (didReceiveScriptMessage.name == "pageFinished") {
                        val url = didReceiveScriptMessage.body?.toString() ?: ""
                        currentUrl.value = url
                        loadingState.value = LoadingState.Finished
                        progress.value = 1.0f
                        canGoBack.value = webView?.canGoBack ?: false
                        canGoForward.value = webView?.canGoForward ?: false
                        webViewClient?.onPageFinished(url)
                    }
                }
            }
            config.userContentController.addScriptMessageHandler(handler, "pageFinished")
            
            val scriptSource = """
                window.addEventListener('DOMContentLoaded', function() {
                    if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.pageFinished) {
                        window.webkit.messageHandlers.pageFinished.postMessage(window.location.href);
                    }
                });
            """.trimIndent()
            val userScript = WKUserScript(
                source = scriptSource,
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentEnd,
                forMainFrameOnly = true
            )
            config.userContentController.addUserScript(userScript)

            val frame = platform.CoreGraphics.CGRectZero.readValue()
            w = WKWebView(frame = frame, configuration = config)
            
            webView = w
            if (initialUrl != null) {
                val url = NSURL.URLWithString(initialUrl)
                if (url != null) {
                    w.loadRequest(NSURLRequest.requestWithURL(url))
                }
            }
        }
        return w
    }

    override fun loadUrl(url: String) {
        val w = getOrCreateWebView()
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl != null) {
            loadingState.value = LoadingState.Loading
            progress.value = 0.1f
            webViewClient?.onPageStarted(url)
            w.loadRequest(NSURLRequest.requestWithURL(nsUrl))
        }
    }

    override fun loadHtml(html: String) {
        val w = getOrCreateWebView()
        w.loadHTMLString(html, null)
    }

    override fun reload() {
        webView?.reload()
    }

    override fun stopLoading() {
        webView?.stopLoading()
    }

    override fun goBack() {
        if (webView?.canGoBack == true) {
            webView?.goBack()
        }
    }

    override fun goForward() {
        if (webView?.canGoForward == true) {
            webView?.goForward()
        }
    }

    override fun evaluateJavascript(script: String, callback: ((String) -> Unit)?) {
        val w = getOrCreateWebView()
        w.evaluateJavaScript(script) { result, error ->
            if (error != null) {
                callback?.invoke("ERROR: ${error.localizedDescription}")
            } else {
                val resString = result?.toString() ?: ""
                callback?.invoke(resString)
            }
        }
    }

    override fun registerJsCallback(name: String, callback: (String) -> Unit) {
        val userContentController = getOrCreateWebView().configuration.userContentController
        val handler = object : NSObject(), platform.WebKit.WKScriptMessageHandlerProtocol {
            override fun userContentController(
                userContentController: platform.WebKit.WKUserContentController,
                didReceiveScriptMessage: platform.WebKit.WKScriptMessage
            ) {
                if (didReceiveScriptMessage.name == name) {
                    val bodyString = didReceiveScriptMessage.body?.toString() ?: ""
                    callback(bodyString)
                }
            }
        }
        userContentController.addScriptMessageHandler(handler, name)
        
        val js = """
            window.$name = {
                postMessage: function(msg) {
                    window.webkit.messageHandlers.$name.postMessage(msg);
                }
            };
        """.trimIndent()
        evaluateJavascript(js, null)
    }

    override fun unregisterJsCallback(name: String) {
        val userContentController = webView?.configuration?.userContentController
        userContentController?.removeScriptMessageHandlerForName(name)
    }

    override fun clearCacheAndCookies() {
        val w = getOrCreateWebView()
        val store = w.configuration.websiteDataStore
        val date = NSDate(timeIntervalSinceReferenceDate = -978307200.0)
        
        val dataTypes = NSSet.setWithArray(listOf(platform.WebKit.WKWebsiteDataTypeCookies))
        
        store.removeDataOfTypes(
            dataTypes,
            modifiedSince = date
        ) {
            // 完成回调
        }
    }

    override fun setWebViewClient(client: KBWebViewClient?) {
        this.webViewClient = client
    }

    override fun setWebChromeClient(client: KBWebChromeClient?) {
        this.webChromeClient = client
    }

    override fun destroy() {
        webView = null
    }
}

@Composable
actual fun KBWebView(
    webView: KBWebView,
    modifier: Modifier
) {
    val iosWebView = webView as? IosWebView ?: return
    androidx.compose.ui.viewinterop.UIKitView(
        factory = {
            iosWebView.getOrCreateWebView()
        },
        modifier = modifier
    )
}

@Composable
actual fun rememberKBWebView(
    initialUrl: String?,
    profile: KBProfile?
): KBWebView {
    val webView = androidx.compose.runtime.remember(initialUrl, profile) {
        IosWebView(initialUrl, profile)
    }
    androidx.compose.runtime.DisposableEffect(webView) {
        onDispose {
            webView.destroy()
        }
    }
    return webView
}

internal actual fun createHeadlessWebView(
    initialUrl: String?,
    profile: KBProfile?
): KBWebView {
    return IosWebView(initialUrl, profile)
}

internal actual suspend fun performClickByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int
) {
    val js = """
        (function() {
            var el = document.elementFromPoint($x, $y);
            if (el) {
                el.click();
                return 'OK';
            }
            return 'NOT_FOUND';
        })()
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

internal actual suspend fun performHoverByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int
) {
    val js = """
        (function() {
            var el = document.elementFromPoint($x, $y);
            if (el) {
                var ev1 = new MouseEvent('mouseover', { bubbles: true });
                var ev2 = new MouseEvent('mouseenter', { bubbles: true });
                el.dispatchEvent(ev1);
                el.dispatchEvent(ev2);
                return 'OK';
            }
            return 'NOT_FOUND';
        })()
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

internal actual suspend fun performScrollByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    deltaX: Int,
    deltaY: Int
) {
    val js = "window.scrollBy($deltaX, $deltaY);"
    webView.evaluateJavascript(js, null)
}

internal actual suspend fun performDragByCoordinates(
    webView: KBWebView,
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int
) {
    val js = """
        (function() {
            var startEl = document.elementFromPoint($startX, $startY);
            if (!startEl) return 'NOT_FOUND';
            
            function fireTouch(type, clientX, clientY) {
                var touch = new Touch({
                    identifier: Date.now(),
                    target: startEl,
                    clientX: clientX,
                    clientY: clientY,
                    screenX: clientX,
                    screenY: clientY
                });
                var touchEvent = new TouchEvent(type, {
                    bubbles: true,
                    cancelable: true,
                    touches: [touch],
                    targetTouches: [touch],
                    changedTouches: [touch]
                });
                startEl.dispatchEvent(touchEvent);
            }
            
            fireTouch('touchstart', $startX, $startY);
            var steps = 5;
            for (var i = 1; i <= steps; i++) {
                var r = i / steps;
                var currX = $startX + ($endX - $startX) * r;
                var currY = $startY + ($endY - $startY) * r;
                fireTouch('touchmove', currX, currY);
            }
            fireTouch('touchend', $endX, $endY);
            return 'OK';
        })()
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

internal actual suspend fun performKeyPress(
    webView: KBWebView,
    key: KeyboardKey
) {
    // WKWebView has no native key injection API; use JS KeyboardEvent dispatch
    val jsKey = keyToJsKey(key)
    val jsCode = keyToJsKeyCode(key)
    val js = """
        (function() {
            var target = document.activeElement || document.body;
            target.dispatchEvent(new KeyboardEvent('keydown', {
                key: '$jsKey', code: '$jsCode', bubbles: true, cancelable: true
            }));
            target.dispatchEvent(new KeyboardEvent('keyup', {
                key: '$jsKey', code: '$jsCode', bubbles: true, cancelable: true
            }));
        })()
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

internal actual suspend fun performKeyCombination(
    webView: KBWebView,
    modifier: KeyboardKey,
    key: KeyboardKey
) {
    // WKWebView: dispatch modifier+key via JS KeyboardEvent
    val modJsKey = keyToJsKey(modifier)
    val modJsCode = keyToJsKeyCode(modifier)
    val jsKey = keyToJsKey(key)
    val jsCode = keyToJsKeyCode(key)
    val js = """
        (function() {
            var target = document.activeElement || document.body;
            var modMap = { 'Control': 'ctrlKey', 'Shift': 'shiftKey', 'Alt': 'altKey', 'Meta': 'metaKey' };
            var modProp = modMap['$modJsKey'] || '';
            var opts = { key: '$jsKey', code: '$jsCode', bubbles: true, cancelable: true };
            if (modProp) opts[modProp] = true;
            target.dispatchEvent(new KeyboardEvent('keydown', opts));
            target.dispatchEvent(new KeyboardEvent('keyup', opts));
        })()
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

internal actual suspend fun performTypeChar(
    webView: KBWebView,
    char: Char
) {
    // WKWebView: dispatch character via JS KeyboardEvent
    val escapedChar = if (char == '\'') "\\\'" else if (char == '\\') "\\\\" else char.toString()
    val js = """
        (function() {
            var target = document.activeElement || document.body;
            target.dispatchEvent(new KeyboardEvent('keydown', {
                key: '$escapedChar', code: 'Key${escapedChar.uppercase()}', bubbles: true, cancelable: true
            }));
            target.dispatchEvent(new KeyboardEvent('keypress', {
                key: '$escapedChar', code: 'Key${escapedChar.uppercase()}', bubbles: true, cancelable: true
            }));
            target.dispatchEvent(new KeyboardEvent('keyup', {
                key: '$escapedChar', code: 'Key${escapedChar.uppercase()}', bubbles: true, cancelable: true
            }));
        })()
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

private fun keyToJsKey(key: KeyboardKey): String = when (key) {
    KeyboardKey.ENTER -> "Enter"
    KeyboardKey.TAB -> "Tab"
    KeyboardKey.ESCAPE -> "Escape"
    KeyboardKey.BACKSPACE -> "Backspace"
    KeyboardKey.DELETE -> "Delete"
    KeyboardKey.ARROW_UP -> "ArrowUp"
    KeyboardKey.ARROW_DOWN -> "ArrowDown"
    KeyboardKey.ARROW_LEFT -> "ArrowLeft"
    KeyboardKey.ARROW_RIGHT -> "ArrowRight"
    KeyboardKey.SHIFT -> "Shift"
    KeyboardKey.CONTROL -> "Control"
    KeyboardKey.ALT -> "Alt"
    KeyboardKey.META -> "Meta"
    KeyboardKey.SPACE -> " "
    KeyboardKey.HOME -> "Home"
    KeyboardKey.END -> "End"
    KeyboardKey.PAGE_UP -> "PageUp"
    KeyboardKey.PAGE_DOWN -> "PageDown"
    KeyboardKey.INSERT -> "Insert"
    KeyboardKey.F1 -> "F1"
    KeyboardKey.F2 -> "F2"
    KeyboardKey.F3 -> "F3"
    KeyboardKey.F4 -> "F4"
    KeyboardKey.F5 -> "F5"
    KeyboardKey.F6 -> "F6"
    KeyboardKey.F7 -> "F7"
    KeyboardKey.F8 -> "F8"
    KeyboardKey.F9 -> "F9"
    KeyboardKey.F10 -> "F10"
    KeyboardKey.F11 -> "F11"
    KeyboardKey.F12 -> "F12"
    KeyboardKey.A -> "a"
    KeyboardKey.C -> "c"
    KeyboardKey.V -> "v"
    KeyboardKey.X -> "x"
    KeyboardKey.S -> "s"
    KeyboardKey.Z -> "z"
}

private fun keyToJsKeyCode(key: KeyboardKey): String = when (key) {
    KeyboardKey.ENTER -> "Enter"
    KeyboardKey.TAB -> "Tab"
    KeyboardKey.ESCAPE -> "Escape"
    KeyboardKey.BACKSPACE -> "Backspace"
    KeyboardKey.DELETE -> "Delete"
    KeyboardKey.ARROW_UP -> "ArrowUp"
    KeyboardKey.ARROW_DOWN -> "ArrowDown"
    KeyboardKey.ARROW_LEFT -> "ArrowLeft"
    KeyboardKey.ARROW_RIGHT -> "ArrowRight"
    KeyboardKey.SHIFT -> "ShiftLeft"
    KeyboardKey.CONTROL -> "ControlLeft"
    KeyboardKey.ALT -> "AltLeft"
    KeyboardKey.META -> "MetaLeft"
    KeyboardKey.SPACE -> "Space"
    KeyboardKey.HOME -> "Home"
    KeyboardKey.END -> "End"
    KeyboardKey.PAGE_UP -> "PageUp"
    KeyboardKey.PAGE_DOWN -> "PageDown"
    KeyboardKey.INSERT -> "Insert"
    KeyboardKey.F1 -> "F1"
    KeyboardKey.F2 -> "F2"
    KeyboardKey.F3 -> "F3"
    KeyboardKey.F4 -> "F4"
    KeyboardKey.F5 -> "F5"
    KeyboardKey.F6 -> "F6"
    KeyboardKey.F7 -> "F7"
    KeyboardKey.F8 -> "F8"
    KeyboardKey.F9 -> "F9"
    KeyboardKey.F10 -> "F10"
    KeyboardKey.F11 -> "F11"
    KeyboardKey.F12 -> "F12"
    KeyboardKey.A -> "KeyA"
    KeyboardKey.C -> "KeyC"
    KeyboardKey.V -> "KeyV"
    KeyboardKey.X -> "KeyX"
    KeyboardKey.S -> "KeyS"
    KeyboardKey.Z -> "KeyZ"
}

internal actual fun performGlobalShutdown() {}
