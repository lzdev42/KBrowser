package xyz.kbrowser.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.cef.network.CefCookieManager
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.callback.CefQueryCallback
import org.cef.callback.CefStringVisitor
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import xyz.kbrowser.jcef.*
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val profileContextMap = java.util.concurrent.ConcurrentHashMap<String, org.cef.browser.CefRequestContext>()

/**
 * OSR 模式状态跟踪。
 * 默认尝试 OSR；若首次创建失败则永久降级到非 OSR，整个生命周期内不再重试。
 * 对调用方透明，不暴露任何配置接口。
 */
object OsrMode {
    @Volatile private var state: State = State.UNKNOWN

    private enum class State { UNKNOWN, OSR_OK, FALLBACK }

    /** 当前是否应该尝试 OSR */
    fun shouldUseOsr(): Boolean = state != State.FALLBACK

    /** 标记 OSR 已成功，后续直接走 OSR */
    fun markOk() { if (state == State.UNKNOWN) state = State.OSR_OK }

    /** 标记 OSR 失败，后续所有实例降级到非 OSR */
    fun markFailed() {
        println("[OsrMode] OSR 初始化失败，本次运行全程降级到非 OSR 模式")
        state = State.FALLBACK
    }
}

class JvmWebView(
    initialUrl: String?,
    profile: KBProfile? = null,
    val isHeadless: Boolean = false
) : KBWebView {
    private val isDestroyed = AtomicBoolean(false)
    private var headlessFrame: javax.swing.JFrame? = null

    val browser: KBCefBrowser

    private val cefBrowser: CefBrowser
        get() = browser.getCefBrowser()

    override val currentUrl = MutableStateFlow<String?>(initialUrl ?: "about:blank")
    override val currentTitle = MutableStateFlow<String?>(null)
    override val loadingState = MutableStateFlow<LoadingState>(LoadingState.Initializing)
    override val progress = MutableStateFlow(0f)

    override val canGoBack = MutableStateFlow(false)
    override val canGoForward = MutableStateFlow(false)

    private var webViewClient: KBWebViewClient? = null
    private var webChromeClient: KBWebChromeClient? = null

    init {
        val client = KBCefApp.getInstance().createClient()
        val isMac = System.getProperty("os.name").lowercase().contains("mac")

        val applyResponsiveScaling = { b: CefBrowser ->
            val comp = b.uiComponent
            if (comp != null) {
                val currentWidth = comp.width
                val targetWidth = 1280.0
                if (currentWidth > 0 && currentWidth < targetWidth) {
                    val scale = currentWidth / targetWidth
                    val zoomLevel = kotlin.math.ln(scale) / kotlin.math.ln(1.2)
                    b.zoomLevel = zoomLevel
                } else {
                    b.zoomLevel = 0.0
                }
            }
        }

        var requestContext: org.cef.browser.CefRequestContext? = null
        if (profile != null) {
            requestContext = profileContextMap.computeIfAbsent(profile.profileId) {
                org.cef.browser.CefRequestContext.createContext(null)
            }
        }

        // OSR 为默认渲染模式，失败时自动降级到非 OSR（对调用方透明）
        fun buildBrowser(useOsr: Boolean): KBCefBrowser {
            val builder = KBCefBrowserBuilder()
                .setUrl(initialUrl ?: "about:blank")
                .setOffScreenRendering(useOsr)
                .setClient(client)
            builder.myRequestContext = requestContext
            if (isMac) builder.myMouseWheelEventEnable = false
            return KBCefBrowser(builder)
        }

        browser = if (OsrMode.shouldUseOsr()) {
            try {
                val b = buildBrowser(useOsr = true)
                OsrMode.markOk()
                b
            } catch (e: Exception) {
                println("[JvmWebView] OSR 创建失败 (${e.message})，降级到非 OSR")
                OsrMode.markFailed()
                buildBrowser(useOsr = false)
            }
        } else {
            buildBrowser(useOsr = false)
        }
        
        if (isMac) {
            cefBrowser.uiComponent?.addMouseWheelListener { e ->
                val invertedEvent = java.awt.event.MouseWheelEvent(
                    e.source as java.awt.Component, e.id, e.`when`, e.modifiersEx,
                    e.x, e.y, e.clickCount, e.isPopupTrigger, e.scrollType, e.scrollAmount,
                    -e.wheelRotation
                )
                // 这里利用反射发送，因为有些 JCEF 版本里 sendMouseWheelEvent 可能未通过接口直接暴露
                try {
                    val method = cefBrowser.javaClass.getMethod("sendMouseWheelEvent", java.awt.event.MouseWheelEvent::class.java)
                    method.invoke(cefBrowser, invertedEvent)
                } catch (ex: Exception) {
                    println("[DEBUG] sendMouseWheelEvent failed: ${ex.message}")
                }
            }
        }
        
        // Register display handler to capture url and title changes
        browser.myCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onAddressChange(b: CefBrowser, f: CefFrame, u: String) {
                currentUrl.value = u
            }

            override fun onTitleChange(b: CefBrowser, t: String) {
                currentTitle.value = t
            }
        }, cefBrowser)

        // Register load handler using our new multi-listener HandlerSupport to isolate callbacks
        val myLoadHandlerInstance = object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(
                b: CefBrowser,
                isLoading: Boolean,
                goBack: Boolean,
                goForward: Boolean
            ) {
                canGoBack.value = goBack
                canGoForward.value = goForward
                if (isLoading) {
                    loadingState.value = LoadingState.Loading
                } else if (loadingState.value !is LoadingState.Error) {
                    loadingState.value = LoadingState.Finished
                    progress.value = 1f
                }
            }

            override fun onLoadStart(b: CefBrowser, f: CefFrame, transitionType: CefRequest.TransitionType) {
                if (f.isMain) {
                    progress.value = 0.1f
                    loadingState.value = LoadingState.Loading
                    webViewClient?.onPageStarted(b.url ?: "")
                }
            }

            override fun onLoadEnd(b: CefBrowser, f: CefFrame, httpStatusCode: Int) {
                if (f.isMain) {
                    progress.value = 1f
                    loadingState.value = LoadingState.Finished
                    webViewClient?.onPageFinished(b.url ?: "")
                    
                    // 重新注入 JS 回调，因为每次页面加载/刷新后 window 上下文会被重置
                    jsCallbacks.forEach { (name, query) ->
                        val script = """
                            window.$name = function(arg) {
                                ${query.inject("arg")}
                            };
                        """.trimIndent()
                        b.executeJavaScript(script, b.url ?: "", 0)
                    }
                }
            }

            override fun onLoadError(
                b: CefBrowser,
                f: CefFrame,
                errorCode: CefLoadHandler.ErrorCode,
                errorText: String,
                failedUrl: String
            ) {
                if (f.isMain && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) {
                    progress.value = 1f
                    val diag = Diagnostics(errorCode.ordinal, errorText, failedUrl)
                    loadingState.value = LoadingState.Error(
                        errorCode = errorCode.ordinal,
                        description = errorText,
                        failingUrl = failedUrl
                    )
                    webViewClient?.onReceivedError(diag)
                }
            }
        }
        browser.myCefClient.addLoadHandler(myLoadHandlerInstance, cefBrowser)

        if (isHeadless) {
            SwingUtilities.invokeLater {
                val frame = javax.swing.JFrame()
                frame.isUndecorated = true
                frame.setSize(1280, 800)
                try {
                    frame.opacity = 0.0f
                } catch (e: Exception) {
                    // 某些窗口管理器或 JDK 不支持半透明/透明窗口时忽略
                }
                frame.contentPane.add(browser.getComponent())
                frame.isVisible = true
                headlessFrame = frame
            }
        }
    }

    override fun loadUrl(url: String) {
        if (!isDestroyed.get()) {
            browser.loadURL(url)
        }
    }

    override fun loadHtml(html: String) {
        if (!isDestroyed.get()) {
            val encoded = Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
            browser.loadURL("data:text/html;charset=utf-8;base64,$encoded")
        }
    }


    override fun reload() {
        if (!isDestroyed.get()) {
            cefBrowser.reload()
        }
    }

    override fun stopLoading() {
        if (!isDestroyed.get()) {
            cefBrowser.stopLoad()
        }
    }

    override fun goBack() {
        if (!isDestroyed.get() && canGoBack.value) {
            cefBrowser.goBack()
        }
    }

    override fun goForward() {
        if (!isDestroyed.get() && canGoForward.value) {
            cefBrowser.goForward()
        }
    }

    override fun evaluateJavascript(script: String, callback: ((String) -> Unit)?) {
        if (isDestroyed.get()) return
        if (callback == null) {
            cefBrowser.executeJavaScript(script, "", 0)
        } else {
            val jsQuery = xyz.kbrowser.jcef.KBCefJSQuery.create(browser)
            jsQuery.addHandler { response ->
                callback(response)
                jsQuery.dispose()
                xyz.kbrowser.jcef.KBCefJSQuery.Response("OK")
            }
            
            val base64Script = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
            val funcName = jsQuery.myFunc.myFuncName
            val jsCode = """
                (function() {
                    try {
                        var scriptText = decodeURIComponent(escape(atob("$base64Script")));
                        var result = window.eval(scriptText);
                        var responseText = (typeof result === 'string' ? result : JSON.stringify(result));
                        window["$funcName"]({
                            request: responseText,
                            onSuccess: function(r){},
                            onFailure: function(e,m){}
                        });
                    } catch(err) {
                        window["$funcName"]({
                            request: "KB_JS_ERROR:" + err.message,
                            onSuccess: function(r){},
                            onFailure: function(e,m){}
                        });
                    }
                })();
            """.trimIndent()
            cefBrowser.executeJavaScript(jsCode, "", 0)
        }
    }

    override fun setWebViewClient(client: KBWebViewClient?) {
        this.webViewClient = client
    }

    override fun setWebChromeClient(client: KBWebChromeClient?) {
        this.webChromeClient = client
    }

    private val jsCallbacks = mutableMapOf<String, KBCefJSQuery>()

    override fun registerJsCallback(name: String, callback: (String) -> Unit) {
        if (isDestroyed.get()) return
        val query = KBCefJSQuery.create(browser)
        query.addHandler { request ->
            callback(request)
            KBCefJSQuery.Response("OK")
        }
        jsCallbacks[name] = query
        val script = """
            window.$name = function(arg) {
                ${query.inject("arg")}
            };
        """.trimIndent()
        cefBrowser.executeJavaScript(script, "", 0)
    }

    override fun unregisterJsCallback(name: String) {
        val query = jsCallbacks.remove(name)
        query?.dispose() // 回收到池
        cefBrowser.executeJavaScript("delete window.$name;", "", 0)
    }

    override fun clearCacheAndCookies() {
        try {
            CefCookieManager.getGlobalManager()?.deleteCookies("", "")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // getOuterHtml removed

    /**
     * 统一坐标转换 JS 模板：
     * 输入文档空间坐标 (docX, docY)，输出视口坐标 + 视口尺寸。
     *
     * 关键设计：
     * 1. 若目标不在当前视口内，数学计算目标滚动位置（不依赖 scrollTo 后 scrollX/Y 同步更新）
     * 2. 返回视口尺寸 (innerWidth, innerHeight) 供 Kotlin 侧做 CSS→AWT 像素转换
     * 3. 文档空间坐标不受窗口大小/滚动影响，保证坐标系统统一稳定
     */
    private fun coordinateTransformJs(docX: Int, docY: Int): String = """
        (function() {
            var docX = $docX;
            var docY = $docY;
            var vw = window.innerWidth;
            var vh = window.innerHeight;
            var scrollX = window.scrollX || window.pageXOffset;
            var scrollY = window.scrollY || window.pageYOffset;

            var clientX = docX - scrollX;
            var clientY = docY - scrollY;

            if (clientX < 0 || clientX > vw || clientY < 0 || clientY > vh) {
                var maxScrollX = Math.max(0, document.documentElement.scrollWidth - vw);
                var maxScrollY = Math.max(0, document.documentElement.scrollHeight - vh);
                var targetScrollX = Math.max(0, Math.min(docX - Math.floor(vw / 2), maxScrollX));
                var targetScrollY = Math.max(0, Math.min(docY - Math.floor(vh / 2), maxScrollY));

                window.scrollTo(targetScrollX, targetScrollY);

                clientX = docX - targetScrollX;
                clientY = docY - targetScrollY;
            }

            return clientX + ',' + clientY + ',' + vw + ',' + vh;
        })();
    """.trimIndent()

    /**
     * 将 CSS 视口坐标转换为 AWT 组件像素坐标。
     * 使用 comp.width/innerWidth 比率，自动适配 zoom、设备缩放、窗口大小变化。
     */
    private fun convertToAwtCoordinates(clientX: Int, clientY: Int, innerWidth: Double, innerHeight: Double, comp: java.awt.Component): Pair<Int, Int> {
        val awtX = (clientX * comp.width.toDouble() / innerWidth).toInt().coerceIn(0, comp.width - 1)
        val awtY = (clientY * comp.height.toDouble() / innerHeight).toInt().coerceIn(0, comp.height - 1)
        return Pair(awtX, awtY)
    }

    private inline fun resolveAwtCoordinates(
        x: Int,
        y: Int,
        crossinline onResolved: (awtX: Int, awtY: Int, comp: java.awt.Component) -> Unit
    ) {
        if (isDestroyed.get()) return
        val jsCode = coordinateTransformJs(x, y)
        evaluateJavascript(jsCode) { result ->
            val parts = result.trim('"').split(',')
            if (parts.size < 4) return@evaluateJavascript
            val clientX = parts[0].toDoubleOrNull()?.toInt() ?: return@evaluateJavascript
            val clientY = parts[1].toDoubleOrNull()?.toInt() ?: return@evaluateJavascript
            val innerWidth = parts[2].toDoubleOrNull() ?: return@evaluateJavascript
            val innerHeight = parts[3].toDoubleOrNull() ?: return@evaluateJavascript

            val comp = cefBrowser.uiComponent ?: return@evaluateJavascript
            val (awtX, awtY) = convertToAwtCoordinates(clientX, clientY, innerWidth, innerHeight, comp)
            onResolved(awtX, awtY, comp)
        }
    }

    // clickBySelector removed

    fun clickByCoordinates(x: Int, y: Int) {
        resolveAwtCoordinates(x, y) { awtX, awtY, comp ->
            val modifiers = java.awt.event.InputEvent.BUTTON1_DOWN_MASK
            cefBrowser.sendMouseEvent(java.awt.event.MouseEvent(comp, java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, awtX, awtY, 0, false, java.awt.event.MouseEvent.NOBUTTON))
            cefBrowser.sendMouseEvent(java.awt.event.MouseEvent(comp, java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), modifiers, awtX, awtY, 1, false, java.awt.event.MouseEvent.BUTTON1))
            cefBrowser.sendMouseEvent(java.awt.event.MouseEvent(comp, java.awt.event.MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), modifiers, awtX, awtY, 1, false, java.awt.event.MouseEvent.BUTTON1))
            cefBrowser.sendMouseEvent(java.awt.event.MouseEvent(comp, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), modifiers, awtX, awtY, 1, false, java.awt.event.MouseEvent.BUTTON1))
        }
    }

    fun hoverByCoordinates(x: Int, y: Int) {
        resolveAwtCoordinates(x, y) { awtX, awtY, comp ->
            cefBrowser.sendMouseEvent(java.awt.event.MouseEvent(comp, java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, awtX, awtY, 0, false, java.awt.event.MouseEvent.NOBUTTON))
        }
    }

    // ===== Native Key Event Methods (JCEF DevTools CDP) =====

    /**
     * Maps [KeyboardKey] to Windows Virtual Key Code (used by CDP Input.dispatchKeyEvent).
     */
    private fun keyToWindowsKeyCode(key: KeyboardKey): Int = when (key) {
        KeyboardKey.ENTER -> 13
        KeyboardKey.TAB -> 9
        KeyboardKey.ESCAPE -> 27
        KeyboardKey.BACKSPACE -> 8
        KeyboardKey.DELETE -> 46
        KeyboardKey.ARROW_UP -> 38
        KeyboardKey.ARROW_DOWN -> 40
        KeyboardKey.ARROW_LEFT -> 37
        KeyboardKey.ARROW_RIGHT -> 39
        KeyboardKey.SHIFT -> 16
        KeyboardKey.CONTROL -> 17
        KeyboardKey.ALT -> 18
        KeyboardKey.META -> 91 // Left Windows / Command key
        KeyboardKey.SPACE -> 32
        KeyboardKey.HOME -> 36
        KeyboardKey.END -> 35
        KeyboardKey.PAGE_UP -> 33
        KeyboardKey.PAGE_DOWN -> 34
        KeyboardKey.INSERT -> 45
        KeyboardKey.F1 -> 112
        KeyboardKey.F2 -> 113
        KeyboardKey.F3 -> 114
        KeyboardKey.F4 -> 115
        KeyboardKey.F5 -> 116
        KeyboardKey.F6 -> 117
        KeyboardKey.F7 -> 118
        KeyboardKey.F8 -> 119
        KeyboardKey.F9 -> 120
        KeyboardKey.F10 -> 121
        KeyboardKey.F11 -> 122
        KeyboardKey.F12 -> 123
        KeyboardKey.A -> 65
        KeyboardKey.C -> 67
        KeyboardKey.V -> 86
        KeyboardKey.X -> 88
        KeyboardKey.S -> 83
        KeyboardKey.Z -> 90
    }

    /**
     * Maps [KeyboardKey] modifier to CDP modifier mask (Alt=1, Ctrl=2, Meta=4, Shift=8).
     */
    private fun keyToCdpModifierMask(key: KeyboardKey): Int = when (key) {
        KeyboardKey.ALT -> 1
        KeyboardKey.CONTROL -> 2
        KeyboardKey.META -> 4
        KeyboardKey.SHIFT -> 8
        else -> 0
    }

    /**
     * Presses a single key using CDP Input.dispatchKeyEvent.
     */
    fun pressKey(key: KeyboardKey) {
        if (isDestroyed.get()) return
        val keyCode = keyToWindowsKeyCode(key)
        val devTools = cefBrowser.devToolsClient ?: return

        // keydown
        devTools.executeDevToolsMethod(
            "Input.dispatchKeyEvent",
            "{\"type\":\"rawKeyDown\",\"windowsVirtualKeyCode\":$keyCode}"
        )
        // keyup
        devTools.executeDevToolsMethod(
            "Input.dispatchKeyEvent",
            "{\"type\":\"keyUp\",\"windowsVirtualKeyCode\":$keyCode}"
        )
    }

    /**
     * Presses a key combination (modifier + key) using CDP.
     */
    fun pressKeyCombination(modifier: KeyboardKey, key: KeyboardKey) {
        if (isDestroyed.get()) return
        val keyCode = keyToWindowsKeyCode(key)
        val modMask = keyToCdpModifierMask(modifier)
        val devTools = cefBrowser.devToolsClient ?: return

        // keydown with modifiers
        devTools.executeDevToolsMethod(
            "Input.dispatchKeyEvent",
            "{\"type\":\"rawKeyDown\",\"windowsVirtualKeyCode\":$keyCode,\"modifiers\":$modMask}"
        )
        // keyup with modifiers
        devTools.executeDevToolsMethod(
            "Input.dispatchKeyEvent",
            "{\"type\":\"keyUp\",\"windowsVirtualKeyCode\":$keyCode,\"modifiers\":$modMask}"
        )
    }

    /**
     * Types a single character using CDP.
     */
    fun typeChar(char: Char) {
        if (isDestroyed.get()) return
        val devTools = cefBrowser.devToolsClient ?: return
        
        // Escape json string properly
        val escaped = char.toString().replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t").replace("\\", "\\\\")
        
        // Use Input.insertText for robust character injection regardless of OS focus state
        devTools.executeDevToolsMethod(
            "Input.insertText",
            "{\"text\":\"$escaped\"}"
        )
    }

    override fun destroy() {
        if (isDestroyed.compareAndSet(false, true)) {
            browser.dispose()
            try {
                headlessFrame?.dispose()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun scrollByCoordinates(x: Int, y: Int, deltaX: Int, deltaY: Int) {
        resolveAwtCoordinates(x, y) { awtX, awtY, comp ->
            val wheelRotation = if (deltaY > 0) 1 else if (deltaY < 0) -1 else 0
            val scrollAmount = kotlin.math.abs(deltaY)
            if (scrollAmount != 0) {
                val wheelEvent = java.awt.event.MouseWheelEvent(
                    comp, java.awt.event.MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                    0, awtX, awtY, 0, false,
                    java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, scrollAmount, wheelRotation
                )
                try {
                    val method = cefBrowser.javaClass.getMethod("sendMouseWheelEvent", java.awt.event.MouseWheelEvent::class.java)
                    method.invoke(cefBrowser, wheelEvent)
                } catch (ex: Exception) {
                    evaluateJavascript("window.scrollBy($deltaX, $deltaY)")
                }
            }
        }
    }

    fun dragByCoordinates(startX: Int, startY: Int, endX: Int, endY: Int) {
        var startAwtX = -1
        var startAwtY = -1
        var endAwtX = -1
        var endAwtY = -1
        var resolveCount = 0
        var targetComp: java.awt.Component? = null

        val onResolved = {
            if (resolveCount == 2 && targetComp != null) {
                val comp = targetComp!!
                val modifiers = java.awt.event.InputEvent.BUTTON1_DOWN_MASK
                cefBrowser.sendMouseEvent(java.awt.event.MouseEvent(comp, java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, startAwtX, startAwtY, 0, false, java.awt.event.MouseEvent.NOBUTTON))
                cefBrowser.sendMouseEvent(java.awt.event.MouseEvent(comp, java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), modifiers, startAwtX, startAwtY, 1, false, java.awt.event.MouseEvent.BUTTON1))
                
                val steps = 5
                for (i in 1..steps) {
                    val ratio = i.toDouble() / steps
                    val currX = (startAwtX + (endAwtX - startAwtX) * ratio).toInt()
                    val currY = (startAwtY + (endAwtY - startAwtY) * ratio).toInt()
                    cefBrowser.sendMouseEvent(java.awt.event.MouseEvent(comp, java.awt.event.MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), modifiers, currX, currY, 0, false, java.awt.event.MouseEvent.NOBUTTON))
                    try { Thread.sleep(20) } catch(e: Exception) {}
                }
                
                cefBrowser.sendMouseEvent(java.awt.event.MouseEvent(comp, java.awt.event.MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), modifiers, endAwtX, endAwtY, 1, false, java.awt.event.MouseEvent.BUTTON1))
            }
        }

        resolveAwtCoordinates(startX, startY) { awtX, awtY, comp ->
            startAwtX = awtX; startAwtY = awtY; targetComp = comp; resolveCount++; onResolved()
        }
        resolveAwtCoordinates(endX, endY) { awtX, awtY, comp ->
            endAwtX = awtX; endAwtY = awtY; targetComp = comp; resolveCount++; onResolved()
        }
    }

}

object JcefWebViewFactory {
    fun create(initialUrl: String?, profile: KBProfile?): KBWebView {
        return JvmWebView(initialUrl, profile)
    }
}

object JcefWebViewRender {
    @Composable
    fun render(webView: KBWebView, modifier: Modifier) {
        val jvmWebView = webView as? JvmWebView ?: return

        SwingPanel(
            factory = {
                jvmWebView.browser.getComponent()
            },
            modifier = modifier
        )
    }
}

@Composable
actual fun KBWebView(
    webView: KBWebView,
    modifier: Modifier
) {
    if (!JcefChecker.isJcefAvailable) {
        androidx.compose.foundation.layout.Box(
            modifier = modifier,
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.material3.Text(
                text = "请将SDK切换为包含JCEF的JBR",
                color = androidx.compose.ui.graphics.Color.Red,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                style = androidx.compose.ui.text.TextStyle(
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            )
        }
        return
    }

    JcefWebViewRender.render(webView, modifier)
}

@Composable
actual fun rememberKBWebView(
    initialUrl: String?,
    profile: KBProfile?
): KBWebView {
    val webView = androidx.compose.runtime.remember(initialUrl, profile) {
        if (JcefChecker.isJcefAvailable) {
            JcefWebViewFactory.create(initialUrl, profile)
        } else {
            FallbackWebView(initialUrl ?: "about:blank")
        }
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
    if (JcefChecker.isJcefAvailable) {
        return JvmWebView(initialUrl, profile = profile, isHeadless = true)
    } else {
        return FallbackWebView(initialUrl ?: "about:blank")
    }
}

internal actual suspend fun performClickByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int
) {
    if (webView is JvmWebView) {
        webView.clickByCoordinates(x, y)
    }
}

internal actual suspend fun performHoverByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int
) {
    if (webView is JvmWebView) {
        webView.hoverByCoordinates(x, y)
    }
}

internal actual suspend fun performScrollByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    deltaX: Int,
    deltaY: Int
) {
    if (webView is JvmWebView) {
        webView.scrollByCoordinates(x, y, deltaX, deltaY)
    }
}

internal actual suspend fun performDragByCoordinates(
    webView: KBWebView,
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int
) {
    if (webView is JvmWebView) {
        webView.dragByCoordinates(startX, startY, endX, endY)
    }
}

internal actual suspend fun performKeyPress(
    webView: KBWebView,
    key: KeyboardKey
) {
    if (webView is JvmWebView) {
        webView.pressKey(key)
    }
}

internal actual suspend fun performKeyCombination(
    webView: KBWebView,
    modifier: KeyboardKey,
    key: KeyboardKey
) {
    if (webView is JvmWebView) {
        webView.pressKeyCombination(modifier, key)
    }
}

internal actual suspend fun performTypeChar(
    webView: KBWebView,
    char: Char
) {
    if (webView is JvmWebView) {
        webView.typeChar(char)
    }
}

internal actual fun performGlobalShutdown() {
    if (JcefChecker.isJcefAvailable) {
        try {
            xyz.kbrowser.jcef.KBCefApp.getInstance().dispose()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
