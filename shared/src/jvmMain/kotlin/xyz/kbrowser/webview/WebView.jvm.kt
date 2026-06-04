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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
                    // sendMouseWheelEvent failed silently
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
                    // 重新注入 JS handler（Promise 包装），原因同上
                    jsHandlers.forEach { (name, query) ->
                        val funcName = query.myFunc.myFuncName
                        val script = """
                            window.$name = function(arg) {
                                return new Promise(function(resolve, reject) {
                                    window["$funcName"]({
                                        request: (typeof arg === 'string' ? arg : JSON.stringify(arg)),
                                        onSuccess: function(r) { resolve(r); },
                                        onFailure: function(code, msg) { reject(new Error(msg || 'handler error')); }
                                    });
                                });
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

        // 拦截新窗口请求（target="_blank"、window.open() 等）
        browser.myCefClient.addLifeSpanHandler(object : org.cef.handler.CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(
                b: org.cef.browser.CefBrowser,
                frame: org.cef.browser.CefFrame,
                targetUrl: String,
                targetFrameName: String
            ): Boolean {
                val handler = onNewWindowRequest
                return if (handler != null) {
                    // 有监听者：通知并阻止默认弹窗
                    handler(targetUrl)
                    true  // true = cancel popup
                } else {
                    // 无监听者：静默阻止（不打开任何东西）
                    true
                }
            }
        }, cefBrowser)

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

    // ── 交互锁定 + 鼠标轨迹 + 点击动画渲染（AWT 层）────────────────────────
    private var lockPanel: javax.swing.JPanel? = null
    private val trailPoints = java.util.concurrent.CopyOnWriteArrayList<java.awt.Point>()
    private val MAX_TRAIL = 30

    // 点击动画：每次点击产生一个扩散圆圈
    private data class ClickRipple(val x: Int, val y: Int, val startMs: Long)
    private val clickRipples = java.util.concurrent.CopyOnWriteArrayList<ClickRipple>()
    private val RIPPLE_DURATION_MS = 500L

    /**
     * 触发点击动画（圆圈扩散效果）。
     * 无论是否锁定都会显示，作为自动化操作的视觉反馈。
     * 坐标为视口坐标（CSS 像素）。
     */
    fun triggerClickAnimation(viewportX: Int, viewportY: Int) {
        val panel = lockPanel ?: return
        clickRipples.add(ClickRipple(viewportX, viewportY, System.currentTimeMillis()))
        // 启动动画定时器
        val timer = javax.swing.Timer(16) { _ ->
            val now = System.currentTimeMillis()
            clickRipples.removeAll { now - it.startMs > RIPPLE_DURATION_MS }
            panel.repaint()
        }
        timer.isRepeats = true
        timer.start()
        // 动画结束后停止定时器
        javax.swing.Timer(RIPPLE_DURATION_MS.toInt() + 50) { timer.stop() }.apply {
            isRepeats = false
            start()
        }
    }

    override fun updateMouseTrail(viewportX: Int, viewportY: Int) {
        val panel = lockPanel ?: return
        trailPoints.add(java.awt.Point(viewportX, viewportY))
        if (trailPoints.size > MAX_TRAIL) trailPoints.removeAt(0)
        SwingUtilities.invokeLater { panel.repaint() }
    }

    /**
     * 锁定/解锁用户交互。
     * locked=true：在 JCEF 组件上覆盖 AWT 面板，拦截所有用户输入，并渲染鼠标轨迹。
     * locked=false：移除面板，恢复用户操作。
     * 自动化操作（CDP）不受影响。
     */
    override fun setInteractionLocked(locked: Boolean) {
        SwingUtilities.invokeLater {
            val comp = cefBrowser.uiComponent ?: return@invokeLater
            val parent = comp.parent ?: return@invokeLater

            if (locked) {
                if (lockPanel != null) return@invokeLater
                val panel = object : javax.swing.JPanel(null) {
                    override fun paintComponent(g: java.awt.Graphics) {
                        super.paintComponent(g)
                        val g2 = g as java.awt.Graphics2D
                        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)

                        // 半透明遮罩
                        g2.color = java.awt.Color(0, 0, 0, 60)
                        g2.fillRect(0, 0, width, height)

                        // 鼠标轨迹
                        val pts = trailPoints.toList()
                        if (pts.size >= 2) {
                            for (i in 1 until pts.size) {
                                val alpha = (i.toFloat() / pts.size * 200).toInt()
                                val size = (i.toFloat() / pts.size * 8).toInt().coerceAtLeast(2)
                                g2.color = java.awt.Color(180, 60, 20, alpha)
                                g2.stroke = java.awt.BasicStroke(size.toFloat(), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                                g2.drawLine(pts[i-1].x, pts[i-1].y, pts[i].x, pts[i].y)
                            }
                        }
                        // 当前光标圆点
                        pts.lastOrNull()?.let { p ->
                            g2.color = java.awt.Color(180, 60, 20, 230)
                            g2.fillOval(p.x - 14, p.y - 14, 28, 28)
                            g2.color = java.awt.Color(255, 255, 255, 200)
                            g2.stroke = java.awt.BasicStroke(2f)
                            g2.drawOval(p.x - 14, p.y - 14, 28, 28)
                        }

                        // 点击扩散动画（隐性商标 😎）
                        val now = System.currentTimeMillis()
                        for (ripple in clickRipples.toList()) {
                            val elapsed = now - ripple.startMs
                            val progress = (elapsed.toFloat() / RIPPLE_DURATION_MS).coerceIn(0f, 1f)
                            val maxRadius = 60
                            val radius = (progress * maxRadius).toInt()
                            val alpha = ((1f - progress) * 255).toInt()
                            // 外圈扩散
                            g2.color = java.awt.Color(180, 60, 20, (alpha * 0.7f).toInt())
                            g2.stroke = java.awt.BasicStroke(2.5f)
                            g2.drawOval(ripple.x - radius, ripple.y - radius, radius * 2, radius * 2)
                            // 内圈实心（点击中心）
                            val innerRadius = ((1f - progress) * 20).toInt().coerceAtLeast(0)
                            g2.color = java.awt.Color(200, 70, 20, alpha)
                            g2.fillOval(ripple.x - innerRadius, ripple.y - innerRadius, innerRadius * 2, innerRadius * 2)
                        }
                    }
                }.apply {
                    isOpaque = false
                    background = java.awt.Color(0, 0, 0, 0)
                    bounds = comp.bounds
                    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR)
                    // 消费所有用户输入事件
                    addMouseListener(object : java.awt.event.MouseAdapter() {})
                    addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {})
                    addMouseWheelListener { /* consume */ }
                    addKeyListener(object : java.awt.event.KeyAdapter() {})
                    isFocusable = true
                }
                lockPanel = panel
                parent.add(panel)
                parent.setComponentZOrder(panel, 0)
                parent.revalidate()
                parent.repaint()
            } else {
                lockPanel?.let { panel ->
                    parent.remove(panel)
                    parent.revalidate()
                    parent.repaint()
                }
                lockPanel = null
                trailPoints.clear()
            }
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

            // 直接把脚本内联执行，不走 base64+eval，避免触发 CSP eval() 限制。
            // 用 Function 构造器也会被 CSP 拦截，所以直接把脚本文本嵌入 IIFE。
            // 脚本本身已经是 (function(){...})() 形式，直接包一层 try/catch 即可。
            val funcName = jsQuery.myFunc.myFuncName
            val jsCode = """
                (function() {
                    try {
                        var result = (function() { $script })();
                        var responseText = (typeof result === 'string' ? result : JSON.stringify(result));
                        window["$funcName"]({
                            request: responseText,
                            onSuccess: function(r){},
                            onFailure: function(e,m){}
                        });
                    } catch(err) {
                        window["$funcName"]({
                            request: "KB_JS_ERROR:" + (err.message || String(err)),
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
    private val jsHandlers = mutableMapOf<String, KBCefJSQuery>()

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
        query?.dispose()
        cefBrowser.executeJavaScript("delete window.$name;", "", 0)
    }

    /**
     * 注册支持 Promise 的双向请求处理器。
     *
     * 底层使用独立的 CefMessageRouter 槽位，handler 的返回值通过
     * CefQueryCallback.success(result) 真正传回 JS 的 Promise resolve。
     *
     * JS 端注入的包装代码：
     * ```javascript
     * window.name = function(arg) {
     *   return new Promise(function(resolve, reject) {
     *     cefQuery_xxx({ request: arg,
     *       onSuccess: function(r) { resolve(r); },
     *       onFailure: function(code, msg) { reject(new Error(msg)); }
     *     });
     *   });
     * };
     * ```
     */
    override fun registerJsHandler(name: String, handler: (String) -> String) {
        if (isDestroyed.get()) return
        val query = KBCefJSQuery.create(browser)
        query.addHandler { request ->
            try {
                val result = handler(request)
                KBCefJSQuery.Response(result)
            } catch (e: Exception) {
                KBCefJSQuery.Response("", errCode = 1, errMsg = e.message ?: "handler error")
            }
        }
        jsHandlers[name] = query
        val funcName = query.myFunc.myFuncName
        // 注入 Promise 包装：onSuccess resolve，onFailure reject
        val script = """
            window.$name = function(arg) {
                return new Promise(function(resolve, reject) {
                    window["$funcName"]({
                        request: (typeof arg === 'string' ? arg : JSON.stringify(arg)),
                        onSuccess: function(r) { resolve(r); },
                        onFailure: function(code, msg) { reject(new Error(msg || 'handler error')); }
                    });
                });
            };
        """.trimIndent()
        cefBrowser.executeJavaScript(script, "", 0)
    }

    override fun unregisterJsHandler(name: String) {
        val query = jsHandlers.remove(name)
        query?.dispose()
        cefBrowser.executeJavaScript("delete window.$name;", "", 0)
    }

    override var onNewWindowRequest: ((url: String) -> Unit)? = null

    override fun clearCacheAndCookies() {
        try {
            CefCookieManager.getGlobalManager()?.deleteCookies("", "")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // getOuterHtml removed

    companion object {
        /** 通过环境变量 KB_CDP_DEBUG=true 启用坐标转换详细日志 */
        private val CDP_DEBUG = System.getenv("KB_CDP_DEBUG") == "true"
    }

    private fun logCoord(msg: String) {
        if (CDP_DEBUG) println("[KB-COORD] $msg")
    }

    /**
     * 获取设备像素比 (DPR)。
     * 使用 AWT 组件的 graphics configuration 获取实际显示缩放比例。
     */
    private fun getDevicePixelRatio(): Double {
        val comp = cefBrowser.uiComponent ?: return 1.0
        return try {
            comp.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        } catch (e: Exception) {
            1.0
        }
    }

    /**
     * Resolves document-space coordinates to AWT component coordinates.
     * Used exclusively by dragByCoordinates (drag is kept on AWT path).
     *
     * Converts CSS viewport coords → AWT physical pixels by multiplying by DPR.
     */
    private suspend fun resolveAwtCoordinates(
        x: Int,
        y: Int,
        onResolved: (awtX: Int, awtY: Int, comp: java.awt.Component) -> Unit
    ) {
        if (isDestroyed.get()) return
        val comp = cefBrowser.uiComponent ?: return
        val devTools = cefBrowser.devToolsClient

        if (devTools != null && !devTools.isClosed) {
            val expr = """
                (function() {
                    var docX = $x, docY = $y;
                    var vw = window.innerWidth, vh = window.innerHeight;
                    var sx = window.scrollX || window.pageXOffset;
                    var sy = window.scrollY || window.pageYOffset;
                    var cx = docX - sx, cy = docY - sy;
                    if (cx < 0 || cx > vw || cy < 0 || cy > vh) {
                        var maxSx = Math.max(0, document.documentElement.scrollWidth - vw);
                        var maxSy = Math.max(0, document.documentElement.scrollHeight - vh);
                        var tSx = Math.max(0, Math.min(docX - Math.floor(vw/2), maxSx));
                        var tSy = Math.max(0, Math.min(docY - Math.floor(vh/2), maxSy));
                        window.scrollTo(tSx, tSy);
                        cx = docX - tSx; cy = docY - tSy;
                    }
                    return cx + ',' + cy;
                })()
            """.trimIndent().replace("\n", " ")

            val resolved = withContext(Dispatchers.IO) {
                try {
                    val escapedExpr = kotlinx.serialization.json.Json.encodeToString(
                        kotlinx.serialization.json.JsonPrimitive(expr)
                    )
                    val resultJson = devTools.executeDevToolsMethod(
                        "Runtime.evaluate",
                        """{"expression":$escapedExpr,"returnByValue":true}"""
                    ).get(10, java.util.concurrent.TimeUnit.SECONDS)
                    if (resultJson != null) {
                        val value = Json.parseToJsonElement(resultJson)
                            .jsonObject["result"]?.jsonObject?.get("result")
                            ?.jsonObject?.get("value")?.jsonPrimitive?.content
                        val parts = value?.split(',')
                        if (parts != null && parts.size >= 2) {
                            val clientX = parts[0].toDoubleOrNull()?.toInt()
                            val clientY = parts[1].toDoubleOrNull()?.toInt()
                            if (clientX != null && clientY != null) Pair(clientX, clientY) else null
                        } else null
                    } else null
                } catch (e: Exception) {
                    null
                }
            }

            if (resolved != null) {
                val dpr = getDevicePixelRatio()
                val awtX = (resolved.first * dpr).toInt()
                val awtY = (resolved.second * dpr).toInt()
                onResolved(awtX, awtY, comp)
                return
            }
        }

        // Fallback: use document coords scaled by DPR (best-effort)
        val dpr = getDevicePixelRatio()
        onResolved((x * dpr).toInt(), (y * dpr).toInt(), comp)
    }

    // clickBySelector removed

    /**
     * Pure CDP click implementation matching Playwright's coordinate system.
     *
     * All coordinates are CSS viewport pixels (relative to viewport top-left).
     * Input.dispatchMouseEvent x/y = CSS viewport coordinates (no DPR scaling needed).
     *
     * Steps:
     * 1. Get scroll position + viewport size via Runtime.evaluate
     * 2. If target is outside viewport, scroll to it and re-fetch scroll position
     * 3. Compute viewport coords: clientX = x - scrollX, clientY = y - scrollY
     * 4. Dispatch mouseMoved → mousePressed → mouseReleased via CDP
     */
    suspend fun clickByCoordinates(x: Int, y: Int) {
        if (isDestroyed.get()) return
        val devTools = cefBrowser.devToolsClient ?: return
        if (devTools.isClosed) return

        // Step 1: get scroll + viewport info
        val scrollInfo = withContext(Dispatchers.IO) {
            try {
                val expr = "window.scrollX + ',' + window.scrollY + ',' + window.innerWidth + ',' + window.innerHeight"
                val escapedExpr = kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.json.JsonPrimitive(expr)
                )
                val resultJson = devTools.executeDevToolsMethod(
                    "Runtime.evaluate",
                    """{"expression":$escapedExpr,"returnByValue":true}"""
                ).get(5, java.util.concurrent.TimeUnit.SECONDS)
                if (resultJson != null) {
                    val root = Json.parseToJsonElement(resultJson)
                    val value = root.jsonObject["result"]?.jsonObject?.get("result")
                        ?.jsonObject?.get("value")?.jsonPrimitive?.content
                        ?: root.jsonObject["result"]?.jsonObject?.get("value")?.jsonPrimitive?.content
                        ?: root.jsonObject["value"]?.jsonPrimitive?.content
                    value?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
                        ?.takeIf { it.size >= 4 }
                } else null
            } catch (e: Exception) {
                logCoord("clickByCoordinates: failed to get scroll info: ${e.message}")
                null
            }
        } ?: return

        var scrollX = scrollInfo[0]
        var scrollY = scrollInfo[1]
        val viewW = scrollInfo[2]
        val viewH = scrollInfo[3]

        // Step 2: scroll into view if needed
        val clientXCheck = x - scrollX
        val clientYCheck = y - scrollY
        if (clientXCheck < 0 || clientXCheck > viewW || clientYCheck < 0 || clientYCheck > viewH) {
            val targetScrollX = (x - viewW / 2).coerceAtLeast(0.0)
            val targetScrollY = (y - viewH / 2).coerceAtLeast(0.0)
            withContext(Dispatchers.IO) {
                try {
                    val scrollExpr = "window.scrollTo($targetScrollX, $targetScrollY); window.scrollX + ',' + window.scrollY"
                    val escapedExpr = kotlinx.serialization.json.Json.encodeToString(
                        kotlinx.serialization.json.JsonPrimitive(scrollExpr)
                    )
                    val resultJson = devTools.executeDevToolsMethod(
                        "Runtime.evaluate",
                        """{"expression":$escapedExpr,"returnByValue":true}"""
                    ).get(5, java.util.concurrent.TimeUnit.SECONDS)
                    if (resultJson != null) {
                        val root = Json.parseToJsonElement(resultJson)
                        val value = root.jsonObject["result"]?.jsonObject?.get("result")
                            ?.jsonObject?.get("value")?.jsonPrimitive?.content
                            ?: root.jsonObject["result"]?.jsonObject?.get("value")?.jsonPrimitive?.content
                            ?: root.jsonObject["value"]?.jsonPrimitive?.content
                        val parts = value?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
                        if (parts != null && parts.size >= 2) {
                            scrollX = parts[0]
                            scrollY = parts[1]
                        }
                    }
                } catch (e: Exception) {
                    logCoord("clickByCoordinates: scroll failed: ${e.message}")
                }
            }
        }

        // Step 3: compute viewport coords (CSS pixels, no DPR scaling)
        val clientX = (x - scrollX).toInt()
        val clientY = (y - scrollY).toInt()
        logCoord("clickByCoordinates: doc=($x,$y) scroll=($scrollX,$scrollY) → client=($clientX,$clientY)")

        // Step 4: dispatch mouse events via CDP
        withContext(Dispatchers.IO) {
            try {
                devTools.executeDevToolsMethod(
                    "Input.dispatchMouseEvent",
                    """{"type":"mouseMoved","x":$clientX,"y":$clientY,"button":"none"}"""
                ).get(5, java.util.concurrent.TimeUnit.SECONDS)
                devTools.executeDevToolsMethod(
                    "Input.dispatchMouseEvent",
                    """{"type":"mousePressed","x":$clientX,"y":$clientY,"button":"left","clickCount":1,"buttons":1}"""
                ).get(5, java.util.concurrent.TimeUnit.SECONDS)
                devTools.executeDevToolsMethod(
                    "Input.dispatchMouseEvent",
                    """{"type":"mouseReleased","x":$clientX,"y":$clientY,"button":"left","clickCount":1,"buttons":0}"""
                ).get(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                logCoord("clickByCoordinates: dispatchMouseEvent failed: ${e.message}")
            }
        }

        // 触发点击动画（视口坐标）
        triggerClickAnimation(clientX, clientY)
        updateMouseTrail(clientX, clientY)
    }

    /**
     * Pure CDP hover implementation using Input.dispatchMouseEvent with type mouseMoved.
     * Coordinates are CSS viewport pixels (same system as clickByCoordinates).
     */
    suspend fun hoverByCoordinates(x: Int, y: Int) {
        if (isDestroyed.get()) return
        val devTools = cefBrowser.devToolsClient ?: return
        if (devTools.isClosed) return

        // Get scroll position to convert document coords to viewport coords
        val scrollInfo = withContext(Dispatchers.IO) {
            try {
                val expr = "window.scrollX + ',' + window.scrollY"
                val escapedExpr = kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.json.JsonPrimitive(expr)
                )
                val resultJson = devTools.executeDevToolsMethod(
                    "Runtime.evaluate",
                    """{"expression":$escapedExpr,"returnByValue":true}"""
                ).get(5, java.util.concurrent.TimeUnit.SECONDS)
                if (resultJson != null) {
                    val root = Json.parseToJsonElement(resultJson)
                    val value = root.jsonObject["result"]?.jsonObject?.get("result")
                        ?.jsonObject?.get("value")?.jsonPrimitive?.content
                        ?: root.jsonObject["result"]?.jsonObject?.get("value")?.jsonPrimitive?.content
                        ?: root.jsonObject["value"]?.jsonPrimitive?.content
                    value?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
                        ?.takeIf { it.size >= 2 }
                } else null
            } catch (e: Exception) {
                null
            }
        } ?: listOf(0.0, 0.0)

        val clientX = (x - scrollInfo[0]).toInt()
        val clientY = (y - scrollInfo[1]).toInt()

        withContext(Dispatchers.IO) {
            try {
                devTools.executeDevToolsMethod(
                    "Input.dispatchMouseEvent",
                    """{"type":"mouseMoved","x":$clientX,"y":$clientY,"button":"none"}"""
                ).get(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                logCoord("hoverByCoordinates: dispatchMouseEvent failed: ${e.message}")
            }
        }

        // 更新鼠标轨迹（悬停移动）
        updateMouseTrail(clientX, clientY)
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
            jsCallbacks.values.forEach { it.dispose() }
            jsCallbacks.clear()
            jsHandlers.values.forEach { it.dispose() }
            jsHandlers.clear()
            browser.dispose()
            try {
                headlessFrame?.dispose()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override suspend fun takeScreenshot(): ByteArray? {
        if (isDestroyed.get()) return null

        val devTools = cefBrowser.devToolsClient ?: return null
        if (devTools.isClosed) return null

        return try {
            // Step 1: capture screenshot via CDP
            val screenshotJson = withContext(Dispatchers.IO) {
                devTools.executeDevToolsMethod(
                    "Page.captureScreenshot",
                    """{"format":"png","captureBeyondViewport":false}"""
                ).get(15, java.util.concurrent.TimeUnit.SECONDS)
            } ?: return null

            val screenshotRoot = Json.parseToJsonElement(screenshotJson).jsonObject
            val base64 = screenshotRoot["data"]?.jsonPrimitive?.content
                ?: screenshotRoot["result"]?.jsonObject?.get("data")?.jsonPrimitive?.content
                ?: screenshotRoot["result"]?.jsonObject?.get("result")?.jsonObject?.get("data")?.jsonPrimitive?.content
                ?: return null

            val pngBytes = java.util.Base64.getDecoder().decode(base64)
            println("[SCREENSHOT] raw PNG size: ${pngBytes.size} bytes")

            // Step 2: get DPR from Page.getLayoutMetrics
            // DPR = layoutViewport.clientWidth / cssLayoutViewport.clientWidth
            // (scale field is page zoom, not device pixel ratio)
            val dpr = withContext(Dispatchers.IO) {
                try {
                    val metricsJson = devTools.executeDevToolsMethod(
                        "Page.getLayoutMetrics",
                        "{}"
                    ).get(5, java.util.concurrent.TimeUnit.SECONDS)
                    if (metricsJson != null) {
                        val metricsRoot = Json.parseToJsonElement(metricsJson).jsonObject

                        fun resolveObj(key: String) =
                            metricsRoot[key]?.jsonObject
                                ?: metricsRoot["result"]?.jsonObject?.get(key)?.jsonObject
                                ?: metricsRoot["result"]?.jsonObject?.get("result")?.jsonObject?.get(key)?.jsonObject

                        val physW = resolveObj("layoutViewport")?.get("clientWidth")?.jsonPrimitive?.content?.toDoubleOrNull()
                        val cssW  = resolveObj("cssLayoutViewport")?.get("clientWidth")?.jsonPrimitive?.content?.toDoubleOrNull()

                        val computed = if (physW != null && cssW != null && cssW > 0) physW / cssW else 1.0
                        println("[SCREENSHOT] layoutVp.clientWidth=$physW cssLayoutVp.clientWidth=$cssW → DPR=$computed")
                        computed
                    } else 1.0
                } catch (e: Exception) {
                    println("[SCREENSHOT] getLayoutMetrics failed: ${e.message}")
                    1.0
                }
            }

            // Step 3: if DPR > 1.0, downscale to CSS pixel size
            if (dpr <= 1.0) {
                println("[SCREENSHOT] DPR=$dpr ≤ 1.0, returning raw bytes")
                pngBytes
            } else {
                val srcImage = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(pngBytes))
                    ?: return pngBytes
                val cssWidth = (srcImage.width / dpr).toInt().coerceAtLeast(1)
                val cssHeight = (srcImage.height / dpr).toInt().coerceAtLeast(1)
                println("[SCREENSHOT] DPR=$dpr, src=${srcImage.width}×${srcImage.height} → css=${cssWidth}×${cssHeight}")
                val scaled = java.awt.image.BufferedImage(cssWidth, cssHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val g2d = scaled.createGraphics()
                try {
                    g2d.setRenderingHint(
                        java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
                    )
                    g2d.drawImage(srcImage, 0, 0, cssWidth, cssHeight, null)
                } finally {
                    g2d.dispose()
                }
                val baos = java.io.ByteArrayOutputStream()
                javax.imageio.ImageIO.write(scaled, "png", baos)
                baos.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun scrollByCoordinates(x: Int, y: Int, deltaX: Int, deltaY: Int) {
        if (isDestroyed.get()) return
        val devTools = cefBrowser.devToolsClient ?: return
        if (devTools.isClosed) return

        // Get scroll position to convert document coords to viewport coords
        val scrollInfo = withContext(Dispatchers.IO) {
            try {
                val expr = "window.scrollX + ',' + window.scrollY"
                val escapedExpr = kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.json.JsonPrimitive(expr)
                )
                val resultJson = devTools.executeDevToolsMethod(
                    "Runtime.evaluate",
                    """{"expression":$escapedExpr,"returnByValue":true}"""
                ).get(5, java.util.concurrent.TimeUnit.SECONDS)
                if (resultJson != null) {
                    val root = Json.parseToJsonElement(resultJson)
                    val value = root.jsonObject["result"]?.jsonObject?.get("result")
                        ?.jsonObject?.get("value")?.jsonPrimitive?.content
                        ?: root.jsonObject["result"]?.jsonObject?.get("value")?.jsonPrimitive?.content
                        ?: root.jsonObject["value"]?.jsonPrimitive?.content
                    value?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
                        ?.takeIf { it.size >= 2 }
                } else null
            } catch (e: Exception) {
                null
            }
        } ?: listOf(0.0, 0.0)

        val clientX = (x - scrollInfo[0]).toInt()
        val clientY = (y - scrollInfo[1]).toInt()

        withContext(Dispatchers.IO) {
            try {
                devTools.executeDevToolsMethod(
                    "Input.dispatchMouseEvent",
                    """{"type":"mouseWheel","x":$clientX,"y":$clientY,"deltaX":$deltaX,"deltaY":$deltaY,"button":"none"}"""
                ).get(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                logCoord("scrollByCoordinates: dispatchMouseEvent failed: ${e.message}")
            }
        }
    }

    suspend fun dragByCoordinates(startX: Int, startY: Int, endX: Int, endY: Int) {
        // 依次解析起点和终点坐标（串行，避免并发修改共享变量）
        var startAwtX = -1
        var startAwtY = -1
        var startComp: java.awt.Component? = null

        resolveAwtCoordinates(startX, startY) { awtX, awtY, comp ->
            startAwtX = awtX; startAwtY = awtY; startComp = comp
        }
        if (startComp == null) return
        val comp = startComp!!

        var endAwtX = -1
        var endAwtY = -1
        resolveAwtCoordinates(endX, endY) { awtX, awtY, _ ->
            endAwtX = awtX; endAwtY = awtY
        }

        val modifiers = java.awt.event.InputEvent.BUTTON1_DOWN_MASK
        cefBrowser.sendMouseEvent(java.awt.event.MouseEvent(comp, java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, startAwtX, startAwtY, 0, false, java.awt.event.MouseEvent.NOBUTTON))
        cefBrowser.sendMouseEvent(java.awt.event.MouseEvent(comp, java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), modifiers, startAwtX, startAwtY, 1, false, java.awt.event.MouseEvent.BUTTON1))

        val steps = 5
        for (i in 1..steps) {
            val ratio = i.toDouble() / steps
            val currX = (startAwtX + (endAwtX - startAwtX) * ratio).toInt()
            val currY = (startAwtY + (endAwtY - startAwtY) * ratio).toInt()
            cefBrowser.sendMouseEvent(java.awt.event.MouseEvent(comp, java.awt.event.MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), modifiers, currX, currY, 0, false, java.awt.event.MouseEvent.NOBUTTON))
            kotlinx.coroutines.delay(20)
        }

        cefBrowser.sendMouseEvent(java.awt.event.MouseEvent(comp, java.awt.event.MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), modifiers, endAwtX, endAwtY, 1, false, java.awt.event.MouseEvent.BUTTON1))

        // 拖拽轨迹：起点→终点，终点触发点击动画（用 CSS 视口坐标）
        updateMouseTrail(startX, startY)
        updateMouseTrail(endX, endY)
        triggerClickAnimation(endX, endY)
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

internal actual suspend fun fetchAxTreeNative(webView: KBWebView): AxTreeData? {
    if (webView !is JvmWebView) return null
    return try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            xyz.kbrowser.jcef.KBCefAxTreeFetcher.fetch(webView.browser.getCefBrowser())
        }
    } catch (e: Exception) {
        null
    }
}

internal actual suspend fun findElementsNative(
    webView: KBWebView,
    selector: String,
    selectorType: KBSelectorType,
    name: String?,
    exact: Boolean
): List<LocateResult>? {
    if (webView !is JvmWebView) return null
    return try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            KBCefLocatorImpl.findAll(
                webView.browser.getCefBrowser(),
                selector, selectorType, name, exact
            )
        }
    } catch (e: Exception) {
        // CDP path failed, return null to fallback to JS path
        null
    }
}
