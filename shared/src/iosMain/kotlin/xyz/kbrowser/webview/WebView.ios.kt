package xyz.kbrowser.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import xyz.kbrowser.webview.debug.KBDebug
import xyz.kbrowser.webview.debug.KBDebugNoop
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKSecurityOrigin
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKWindowFeatures
import platform.Foundation.*
import platform.darwin.NSObject
import kotlinx.cinterop.readValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.*
import kotlin.coroutines.resume

/**
 * Named class implementing WKNavigationDelegateProtocol.
 *
 * A named class is used instead of an anonymous object because Kotlin/Native on iOS may
 * register Obj-C protocol methods of anonymous objects unreliably (with several same-named
 * webView: overloads, some overrides can be missed by the Obj-C runtime). Named-class
 * method registration is more dependable.
 */
@OptIn(ExperimentalForeignApi::class)
private class KbNavigationDelegate(
    private val iosWebView: IosWebView
) : NSObject(), platform.WebKit.WKNavigationDelegateProtocol {
    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didStartProvisionalNavigation: platform.WebKit.WKNavigation?
    ) {
        val url = webView.URL?.absoluteString ?: ""
        iosWebView.currentUrl.value = url
        iosWebView.loadingState.value = LoadingState.Loading
        iosWebView.progress.value = 0.1f
        iosWebView.webViewClient?.onPageStarted(url)
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFinishNavigation: platform.WebKit.WKNavigation?
    ) {
        val url = webView.URL?.absoluteString ?: ""
        if (iosWebView.isDuplicateFinishNotification(url)) return
        iosWebView.currentUrl.value = url
        iosWebView.loadingState.value = LoadingState.Finished
        iosWebView.progress.value = 1.0f
        iosWebView.canGoBack.value = webView.canGoBack
        iosWebView.canGoForward.value = webView.canGoForward
        iosWebView.webViewClient?.onPageFinished(url)
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: platform.WebKit.WKNavigation?,
        withError: NSError
    ) {
        val url = webView.URL?.absoluteString ?: ""
        val errorCode = withError.code.toInt()
        val description = withError.localizedDescription
        iosWebView.loadingState.value = LoadingState.Error(errorCode, description, url)
        iosWebView.webViewClient?.onReceivedError(Diagnostics(errorCode, description, url))
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailNavigation: platform.WebKit.WKNavigation?,
        withError: NSError
    ) {
        val url = webView.URL?.absoluteString ?: ""
        val errorCode = withError.code.toInt()
        val description = withError.localizedDescription
        iosWebView.loadingState.value = LoadingState.Error(errorCode, description, url)
        iosWebView.webViewClient?.onReceivedError(Diagnostics(errorCode, description, url))
    }
}

private class KbUIDelegate(
    private val getChromeClient: () -> KBWebChromeClient?,
    private val getOnNewWindowRequest: () -> ((String) -> Unit)?
) : NSObject(), WKUIDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ): WKWebView? {
        val targetUrl = forNavigationAction.request.URL?.absoluteString ?: return null
        getOnNewWindowRequest()?.invoke(targetUrl)
        return null
    }

    override fun webView(
        webView: WKWebView,
        runJavaScriptAlertPanelWithMessage: String,
        initiatedByFrame: WKFrameInfo,
        completionHandler: () -> Unit
    ) {
        println("[KB_IOS_UIDELEGATE] alert called: $runJavaScriptAlertPanelWithMessage")
        val client = getChromeClient()
        if (client == null) {
            completionHandler()
            return
        }
        client.onJsAlert("", runJavaScriptAlertPanelWithMessage, object : JsResultCallback {
            override fun confirm() { completionHandler() }
            override fun cancel() { completionHandler() }
        })
    }

    override fun webView(
        webView: WKWebView,
        runJavaScriptConfirmPanelWithMessage: String,
        initiatedByFrame: WKFrameInfo,
        completionHandler: (Boolean) -> Unit
    ) {
        println("[KB_IOS_UIDELEGATE] confirm called: $runJavaScriptConfirmPanelWithMessage")
        val client = getChromeClient()
        if (client == null) {
            completionHandler(false)
            return
        }
        client.onJsConfirm("", runJavaScriptConfirmPanelWithMessage, object : JsResultCallback {
            override fun confirm() { completionHandler(true) }
            override fun cancel() { completionHandler(false) }
        })
    }

    override fun webView(
        webView: WKWebView,
        runJavaScriptTextInputPanelWithPrompt: String,
        defaultText: String?,
        initiatedByFrame: WKFrameInfo,
        completionHandler: (String?) -> Unit
    ) {
        println("[KB_IOS_UIDELEGATE] prompt called: $runJavaScriptTextInputPanelWithPrompt default=$defaultText")
        val client = getChromeClient()
        if (client == null) {
            completionHandler(null)
            return
        }
        client.onJsPrompt("", runJavaScriptTextInputPanelWithPrompt, defaultText, object : JsPromptResultCallback {
            override fun confirm(value: String?) { completionHandler(value) }
            override fun cancel() { completionHandler(null) }
        })
    }

    override fun webView(
        webView: WKWebView,
        requestMediaCapturePermissionForOrigin: WKSecurityOrigin,
        initiatedByFrame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: (WKPermissionDecision) -> Unit
    ) {
        val resources = when (type) {
            WKMediaCaptureType.WKMediaCaptureTypeCamera -> listOf(PermissionResource.VIDEO_CAPTURE)
            WKMediaCaptureType.WKMediaCaptureTypeMicrophone -> listOf(PermissionResource.AUDIO_CAPTURE)
            WKMediaCaptureType.WKMediaCaptureTypeCameraAndMicrophone -> listOf(PermissionResource.VIDEO_CAPTURE, PermissionResource.AUDIO_CAPTURE)
            else -> emptyList()
        }
        val origin = requestMediaCapturePermissionForOrigin.toString()
        val kbRequest = object : PermissionRequest {
            override val origin: String = origin
            override val resources: List<PermissionResource> = resources
            override fun grant() = decisionHandler(WKPermissionDecision.WKPermissionDecisionGrant)
            override fun deny() = decisionHandler(WKPermissionDecision.WKPermissionDecisionDeny)
        }
        val client = getChromeClient()
        if (client == null) {
            decisionHandler(WKPermissionDecision.WKPermissionDecisionDeny)
            return
        }
        client.onPermissionRequest(kbRequest)
    }
}

class IosWebView(
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

    override var backgroundColor: Color = Color.Black

    private var webView: WKWebView? = null
    internal var webViewClient: KBWebViewClient? = null
    internal var webChromeClient: KBWebChromeClient? = null
    private var retainedUiDelegate: NSObject? = null
    private var retainedNavDelegate: NSObject? = null

    // Registered JS callbacks/handlers, kept for re-injection after page loads
    private val jsCallbackMap = mutableMapOf<String, (String) -> Unit>()
    private val jsCallbackHandlerMap = mutableMapOf<String, NSObject>()
    private val jsHandlerMap = mutableMapOf<String, (String) -> String>()
    private val jsHandlerHandlerMap = mutableMapOf<String, NSObject>()

    /**
     * WKNavigationDelegate.didFinishNavigation and the DOMContentLoaded pageFinished message
     * each fire once per load, and didStartProvisionalNavigation resets the state back to
     * Loading, so "already Finished with an unchanged URL" identifies a duplicate notification.
     */
    internal fun isDuplicateFinishNotification(url: String): Boolean =
        loadingState.value == LoadingState.Finished && currentUrl.value == url

    @OptIn(ExperimentalForeignApi::class)
    fun getOrCreateWebView(): WKWebView {
        var w = webView
        if (w == null) {
            val store = if (profile != null) {
                try {
                    WKWebsiteDataStore.dataStoreForIdentifier(NSUUID(uUIDString = profile.profileId))
                } catch (e: Throwable) {
                    WKWebsiteDataStore.nonPersistentDataStore()
                }
            } else {
                WKWebsiteDataStore.defaultDataStore()
            }
            
            val config = WKWebViewConfiguration().apply {
                websiteDataStore = store
                // WKWebView enables JS by default; explicitly allow JS to open windows (needed by alert/confirm/prompt)
                preferences.javaScriptCanOpenWindowsAutomatically = true
                allowsInlineMediaPlayback = true
                // iOS 14+ allows content JS by default; confirm explicitly when the API is available
                try {
                    defaultWebpagePreferences.setAllowsContentJavaScript(true)
                } catch (_: Exception) {}
            }
            
            // pageFinished message listener updates page state, replacing the conflicting
            // navigationDelegate-based finish detection
            val handler = object : NSObject(), platform.WebKit.WKScriptMessageHandlerProtocol {
                override fun userContentController(
                    userContentController: platform.WebKit.WKUserContentController,
                    didReceiveScriptMessage: platform.WebKit.WKScriptMessage
                ) {
                    if (didReceiveScriptMessage.name == "pageFinished") {
                        val url = didReceiveScriptMessage.body.toString()
                        if (isDuplicateFinishNotification(url)) return
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

            installDocumentStartScripts(config.userContentController)

            val screenBounds = platform.UIKit.UIScreen.mainScreen.bounds
            val vpW = viewportWidth?.toDouble() ?: screenBounds.useContents { size.width }
            val vpH = viewportHeight?.toDouble() ?: screenBounds.useContents { size.height }
            val frame = platform.CoreGraphics.CGRectMake(0.0, 0.0, vpW, vpH)
            w = WKWebView(frame = frame, configuration = config)

            val uiDelegate = KbUIDelegate({ webChromeClient }, { onNewWindowRequest })
            w.UIDelegate = uiDelegate
            retainedUiDelegate = uiDelegate

            val navDelegate = KbNavigationDelegate(this)
            w.navigationDelegate = navDelegate
            retainedNavDelegate = navDelegate

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
        // about:blank as baseURL: baseURL=nil makes some iOS versions restrict JS execution/alerts
        w.loadHTMLString(html, NSURL.URLWithString("about:blank"))
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
        jsCallbackMap[name] = callback
        val userContentController = getOrCreateWebView().configuration.userContentController
        // Remove an existing handler of the same name first; WKUserContentController crashes on duplicate registration
        userContentController.removeScriptMessageHandlerForName(name)
        val handler = object : NSObject(), platform.WebKit.WKScriptMessageHandlerProtocol {
            override fun userContentController(
                userContentController: platform.WebKit.WKUserContentController,
                didReceiveScriptMessage: platform.WebKit.WKScriptMessage
            ) {
                if (didReceiveScriptMessage.name == name) {
                    val bodyString = didReceiveScriptMessage.body.toString()
                    callback(bodyString)
                }
            }
        }
        jsCallbackHandlerMap[name] = handler
        userContentController.addScriptMessageHandler(handler, name)
        installDocumentStartScripts(userContentController)
        injectJsCallbackFunction(name)
    }

    private fun callbackFunctionSource(name: String): String = """
            window.$name = function(msg) {
                window.webkit.messageHandlers.$name.postMessage(
                    typeof msg === 'string' ? msg : JSON.stringify(msg)
                );
            };
    """.trimIndent()

    private fun injectJsCallbackFunction(name: String) {
        evaluateJavascript(callbackFunctionSource(name), null)
    }

    override fun unregisterJsCallback(name: String) {
        jsCallbackMap.remove(name)
        jsCallbackHandlerMap.remove(name)
        val userContentController = webView?.configuration?.userContentController
        userContentController?.removeScriptMessageHandlerForName(name)
        installDocumentStartScripts(userContentController ?: return)
        evaluateJavascript("delete window.$name;", null)
    }

    /**
     * iOS implementation: a callback-id mechanism delivers the Promise result back to JS
     * via evaluateJavaScript.
     */
    override fun registerJsHandler(name: String, handler: (String) -> String) {
        jsHandlerMap[name] = handler
        val w = getOrCreateWebView()
        val userContentController = w.configuration.userContentController
        userContentController.removeScriptMessageHandlerForName(name)

        val messageHandler = object : NSObject(), platform.WebKit.WKScriptMessageHandlerProtocol {
            override fun userContentController(
                userContentController: platform.WebKit.WKUserContentController,
                didReceiveScriptMessage: platform.WebKit.WKScriptMessage
            ) {
                val bodyString = didReceiveScriptMessage.body.toString()
                var callbackId: String? = null
                try {
                    val json = kotlinx.serialization.json.Json.decodeFromString<kotlinx.serialization.json.JsonObject>(bodyString)
                    callbackId = json["__kb_cb_id"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                    if (callbackId == null) return
                    val data = json["data"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: ""
                    val result = handler(data)
                    val escaped = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), result)
                    evaluateJavascript("if(window.__kb_handler_cb&&window.__kb_handler_cb['$callbackId']){window.__kb_handler_cb['$callbackId'].resolve($escaped);delete window.__kb_handler_cb['$callbackId'];}", null)
                } catch (e: Exception) {
                    val errorMsg = e.message ?: "handler error"
                    val escaped = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), errorMsg)
                    val cbId = callbackId ?: ""
                    evaluateJavascript("if(window.__kb_handler_cb&&window.__kb_handler_cb['$cbId']){window.__kb_handler_cb['$cbId'].reject(new Error($escaped));delete window.__kb_handler_cb['$cbId'];}", null)
                }
            }
        }
        jsHandlerHandlerMap[name] = messageHandler
        userContentController.addScriptMessageHandler(messageHandler, name)
        installDocumentStartScripts(userContentController)
        injectJsHandlerFunction(name)
    }

    private fun handlerFunctionSource(name: String): String = """
            window.__kb_handler_cb = window.__kb_handler_cb || {};
            window.__kb_handler_cb_id = window.__kb_handler_cb_id || 0;
            window.$name = function(arg) {
                return new Promise(function(resolve, reject) {
                    var id = 'cb_' + (++window.__kb_handler_cb_id);
                    window.__kb_handler_cb[id] = {resolve: resolve, reject: reject};
                    window.webkit.messageHandlers.$name.postMessage(JSON.stringify({
                        __kb_cb_id: id,
                        data: typeof arg === 'string' ? arg : JSON.stringify(arg)
                    }));
                });
            };
    """.trimIndent()

    private fun injectJsHandlerFunction(name: String) {
        evaluateJavascript(handlerFunctionSource(name), null)
    }

    override fun unregisterJsHandler(name: String) {
        jsHandlerMap.remove(name)
        jsHandlerHandlerMap.remove(name)
        val userContentController = webView?.configuration?.userContentController
        userContentController?.removeScriptMessageHandlerForName(name)
        installDocumentStartScripts(userContentController ?: return)
        evaluateJavascript("delete window.$name;", null)
    }

    private val pageFinishedScriptSource = """
        window.addEventListener('DOMContentLoaded', function() {
            if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.pageFinished) {
                window.webkit.messageHandlers.pageFinished.postMessage(window.location.href);
            }
        });
    """.trimIndent()

    /**
     * Rebuilds the document-start user scripts: the pageFinished listener plus all registered
     * JS callbacks/handlers. WKUserScript is injected automatically at document start of every
     * navigation (including reload, goBack, loadHTMLString), so early page scripts can call the
     * bridge functions without waiting for a post-onPageFinished injection.
     */
    private fun installDocumentStartScripts(userContentController: platform.WebKit.WKUserContentController) {
        userContentController.removeAllUserScripts()
        userContentController.addUserScript(
            WKUserScript(
                source = pageFinishedScriptSource,
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentEnd,
                forMainFrameOnly = true
            )
        )
        val bridgeSource = buildString {
            jsCallbackMap.keys.forEach { append(callbackFunctionSource(it)).append('\n') }
            jsHandlerMap.keys.forEach { append(handlerFunctionSource(it)).append('\n') }
        }.trim()
        if (bridgeSource.isNotEmpty()) {
            userContentController.addUserScript(
                WKUserScript(
                    source = bridgeSource,
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                    forMainFrameOnly = true
                )
            )
        }
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
        }
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
        webView = null
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun takeScreenshot(): KBScreenshot? {
        val w = webView ?: return null
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val config = platform.WebKit.WKSnapshotConfiguration()
            w.takeSnapshotWithConfiguration(config) { image, error ->
                if (error != null || image == null) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                    return@takeSnapshotWithConfiguration
                }

                val targetImage = if (image.scale > 1.0) {
                    val size = image.size
                    platform.UIKit.UIGraphicsBeginImageContextWithOptions(size, false, 1.0)
                    image.drawInRect(platform.CoreGraphics.CGRectMake(0.0, 0.0, size.useContents { width }, size.useContents { height }))
                    val scaled = platform.UIKit.UIGraphicsGetImageFromCurrentImageContext()
                    platform.UIKit.UIGraphicsEndImageContext()
                    scaled ?: image
                } else {
                    image
                }

                val nsData = platform.UIKit.UIImagePNGRepresentation(targetImage)
                if (nsData != null) {
                    val bytes = ByteArray(nsData.length.toInt())
                    bytes.usePinned { pinned ->
                        platform.posix.memcpy(pinned.addressOf(0), nsData.bytes, nsData.length)
                    }
                    val cssWidth = targetImage.size.useContents { width.toInt() }
                    val cssHeight = targetImage.size.useContents { height.toInt() }
                    if (continuation.isActive) {
                        continuation.resume(KBScreenshot(bytes, cssWidth, cssHeight))
                    }
                } else {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }
}

internal actual fun setInteractionLockedNative(webView: KBWebView, locked: Boolean) { /* iOS: not implemented */ }
internal actual fun updateMouseTrailNative(webView: KBWebView, viewportX: Int, viewportY: Int) { /* iOS: not implemented */ }

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

internal actual fun createPageWebView(
    initialUrl: String?,
    profile: KBProfile?,
    viewportWidth: Int?,
    viewportHeight: Int?
): KBWebView {
    return IosWebView(initialUrl, profile, viewportWidth, viewportHeight)
}

internal actual suspend fun performClickByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    popupSelector: String?
): Pair<Int, Int>? {
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
    return null
}

internal actual suspend fun performHoverByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    popupSelector: String?
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

internal actual suspend fun fetchAxTreeNative(webView: KBWebView): AxTreeData? = null

internal actual suspend fun findElementsNative(
    webView: KBWebView,
    selector: String,
    selectorType: KBSelectorType,
    name: String?,
    exact: Boolean
): List<LocateResult>? = null  // iOS uses JS fallback

private suspend fun KBWebView.evaluateJsSuspend(script: String): String {
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            evaluateJavascript(script) { result ->
                // iOS evaluateJavascript callback always returns a string, or ERROR if failed.
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
    throw UnsupportedOperationException("File upload via CDP is not supported on iOS. Use native file dialog instead.")
}
