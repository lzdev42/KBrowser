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
import xyz.kbrowser.jcef.KBCefApp
import xyz.kbrowser.jcef.KBCefBrowser
import xyz.kbrowser.jcef.KBCefBrowserBuilder
import xyz.kbrowser.jcef.KBCefJSQuery
import javax.swing.SwingUtilities
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.event.InputEvent
import java.awt.event.MouseEvent

class JvmWebViewController(initialUrl: String = "about:blank", isOsr: Boolean = false) : WebViewController {
    private val isDestroyed = AtomicBoolean(false)

    private val funcName = "_q_" + UUID.randomUUID().toString().replace("-", "")
    private val pendingCallbacks = java.util.concurrent.ConcurrentHashMap<String, (String) -> Unit>()
    private val jsQueryRouter: CefMessageRouter

    val browser: KBCefBrowser

    private val cefBrowser: CefBrowser
        get() = browser.getCefBrowser()

    override val currentUrl = MutableStateFlow<String?>(initialUrl)
    override val currentTitle = MutableStateFlow<String?>(null)
    override val loadingState = MutableStateFlow<LoadingState>(LoadingState.Initializing)
    override val progress = MutableStateFlow(0f)

    override val canGoBack = MutableStateFlow(false)
    override val canGoForward = MutableStateFlow(false)

    private val _visualIndicatorFlow = kotlinx.coroutines.flow.MutableSharedFlow<VisualIndicatorEvent>(extraBufferCapacity = 10)

    // AWT 遮罩 Glass Pane，盖在浏览器上面
    val maskGlassPane = MaskGlassPane()

    init {
        // 1. Create client first
        val client = KBCefApp.getInstance().createClient()

        // 2. Register the message router before browser creation to prevent V8 context injection race conditions!
        val config = CefMessageRouter.CefMessageRouterConfig()
        config.jsQueryFunction = funcName
        config.jsCancelFunction = funcName + "_cancel"
        jsQueryRouter = CefMessageRouter.create(config)
        client.cefClient.addMessageRouter(jsQueryRouter)
        
        // 3. Create the browser with this fully configured client
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        val builder = KBCefBrowserBuilder()
            .setUrl(initialUrl)
            .setOffScreenRendering(isOsr)
            .setClient(client)
        if (isMac) {
            builder.myMouseWheelEventEnable = false
        }
        browser = KBCefBrowser(builder)
        
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
        
        jsQueryRouter.addHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(
                b: CefBrowser,
                f: CefFrame,
                queryId: Long,
                request: String,
                persistent: Boolean,
                callback: CefQueryCallback
            ): Boolean {
                val colonIndex = request.indexOf(':')
                if (colonIndex != -1) {
                    val qId = request.substring(0, colonIndex)
                    val payload = request.substring(colonIndex + 1)
                    println("[DEBUG-onQuery] 收到 JS 回调: qId=$qId, payload长度=${payload.length}, payload前100=${payload.take(100)}")
                    val cb = pendingCallbacks.remove(qId)
                    if (cb != null) {
                        println("[DEBUG-onQuery] ✅ 找到匹配回调，准备执行")
                        cb(payload)
                        callback.success("OK")
                        return true
                    } else {
                        println("[DEBUG-onQuery] ❌ 未找到匹配回调 qId=$qId, pendingCallbacks keys=${pendingCallbacks.keys}")
                    }
                }
                callback.failure(1, "No handler found")
                return true
            }
        }, false)

        // Register display handler to capture url and title changes
        browser.myCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onAddressChange(b: CefBrowser, f: CefFrame, u: String) {
                currentUrl.value = u
            }

            override fun onTitleChange(b: CefBrowser, t: String) {
                currentTitle.value = t
            }
        }, cefBrowser)

        // Register load handler directly with the underlying CefClient to bypass KBCefClient limits
        browser.myCefClient.cefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
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
                }
            }

            override fun onLoadEnd(b: CefBrowser, f: CefFrame, httpStatusCode: Int) {
                if (f.isMain) {
                    progress.value = 1f
                    loadingState.value = LoadingState.Finished
                }
            }

            override fun onLoadError(
                b: CefBrowser,
                f: CefFrame,
                errorCode: CefLoadHandler.ErrorCode,
                errorText: String,
                failedUrl: String
            ) {
                if (f.isMain) {
                    progress.value = 1f
                    loadingState.value = LoadingState.Error(
                        errorCode = errorCode.ordinal,
                        description = errorText,
                        failingUrl = failedUrl
                    )
                }
            }
        })

        // 动态监听组件大小变化，让网页自动缩放到适合当前视口的尺寸 (Desktop Responsive Scaling)
        cefBrowser.uiComponent?.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                val comp = e.component
                val currentWidth = comp.width
                // 假设绝大部分桌面端网页的目标设计宽度为 1280 像素
                val targetWidth = 1280.0
                if (currentWidth > 0 && currentWidth < targetWidth) {
                    val scale = currentWidth / targetWidth
                    // Chromium 的 zoomLevel 公式: scale = 1.2 ^ zoomLevel
                    val zoomLevel = kotlin.math.ln(scale) / kotlin.math.ln(1.2)
                    cefBrowser.zoomLevel = zoomLevel
                } else {
                    cefBrowser.zoomLevel = 0.0
                }
            }
        })
    }

    override fun loadUrl(url: String) {
        if (!isDestroyed.get()) {
            browser.loadURL(url)
        }
    }

    override fun loadHtml(html: String, baseUrl: String) {
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

    override fun evaluateJavaScript(script: String, callback: ((String) -> Unit)?) {
        if (isDestroyed.get()) return
        if (callback == null) {
            cefBrowser.executeJavaScript(script, "", 0)
        } else {
            val queryId = "q_" + System.currentTimeMillis() + "_" + (1000..9999).random()
            pendingCallbacks[queryId] = callback
            
            val base64Script = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
            val jsCode = """
                (function() {
                    try {
                        var scriptText = decodeURIComponent(escape(atob("$base64Script")));
                        var result = window.eval(scriptText);
                        var responseText = (typeof result === 'string' ? result : JSON.stringify(result));
                        window.$funcName({
                            request: "$queryId:" + responseText,
                            onSuccess: function(r){},
                            onFailure: function(e,m){}
                        });
                    } catch(err) {
                        window.$funcName({
                            request: "$queryId:ERROR:" + err.message,
                            onSuccess: function(r){},
                            onFailure: function(e,m){}
                        });
                    }
                })();
            """.trimIndent()
            cefBrowser.executeJavaScript(jsCode, "", 0)
        }
    }

    private val jsCallbacks = mutableMapOf<String, KBCefJSQuery>()

    override fun registerJsCallback(name: String, callback: (String) -> String) {
        if (isDestroyed.get()) return
        val query = KBCefJSQuery(browser)
        query.addHandler { request ->
            callback(request)
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
        jsCallbacks.remove(name)
        cefBrowser.executeJavaScript("delete window.$name;", "", 0)
    }

    override fun clearCacheAndCookies() {
        try {
            CefCookieManager.getGlobalManager()?.deleteCookies("", "")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getOuterHtml(callback: (String) -> Unit) {
        val js = """
            (function() {
                var html = document.documentElement.outerHTML;
                // 诊断信息：视口和缩放状态
                var diag = {
                    url: window.location.href,
                    innerWidth: window.innerWidth,
                    innerHeight: window.innerHeight,
                    outerWidth: window.outerWidth,
                    outerHeight: window.outerHeight,
                    scrollX: window.scrollX,
                    scrollY: window.scrollY,
                    devicePixelRatio: window.devicePixelRatio,
                    screenWidth: window.screen.width,
                    screenHeight: window.screen.height,
                    htmlLength: html.length
                };
                return JSON.stringify({diagnostics: diag, html: html});
            })()
        """.trimIndent()
        evaluateJavaScript(js) { result ->
            println("[DEBUG-getOuterHtml] 返回长度=${result.length}, 前100=${result.take(100)}")
            callback(result)
        }
    }

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

    override fun clickBySelector(selector: String) {
        if (isDestroyed.get()) return
        val jsCode = """
            (function() {
                var el = document.querySelector('$selector');
                if (!el) return 'NOT_FOUND';
                var rect = el.getBoundingClientRect();
                var docX = Math.round(rect.left + rect.width / 2 + window.scrollX);
                var docY = Math.round(rect.top + rect.height / 2 + window.scrollY);
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

        evaluateJavaScript(jsCode) { result ->
            println("[DEBUG-clickBySelector] JS 回调结果: result='$result'")
            if (result.trim('"') == "NOT_FOUND") {
                println("[DEBUG-clickBySelector] ❌ 元素未找到")
                return@evaluateJavaScript
            }
            val parts = result.trim('"').split(',')
            if (parts.size < 4) {
                println("[DEBUG-clickBySelector] ❌ 返回格式不正确，parts=$parts")
                return@evaluateJavaScript
            }
            val clientX = parts[0].toDoubleOrNull()?.toInt() ?: return@evaluateJavaScript
            val clientY = parts[1].toDoubleOrNull()?.toInt() ?: return@evaluateJavaScript
            val innerWidth = parts[2].toDoubleOrNull() ?: return@evaluateJavaScript
            val innerHeight = parts[3].toDoubleOrNull() ?: return@evaluateJavaScript

            val comp = cefBrowser.uiComponent ?: return@evaluateJavaScript
            val (awtX, awtY) = convertToAwtCoordinates(clientX, clientY, innerWidth, innerHeight, comp)

            println("[DEBUG-clickBySelector] CSS视口坐标=($clientX, $clientY), 视口尺寸=${innerWidth}x${innerHeight}, AWT组件尺寸=${comp.width}x${comp.height}, AWT坐标=($awtX, $awtY)")

            maskGlassPane.addClickAnimation(awtX, awtY)

            val modifiers = InputEvent.BUTTON1_DOWN_MASK
            cefBrowser.sendMouseEvent(MouseEvent(comp, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, awtX, awtY, 0, false, MouseEvent.NOBUTTON))
            cefBrowser.sendMouseEvent(MouseEvent(comp, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), modifiers, awtX, awtY, 1, false, MouseEvent.BUTTON1))
            cefBrowser.sendMouseEvent(MouseEvent(comp, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), modifiers, awtX, awtY, 1, false, MouseEvent.BUTTON1))
            cefBrowser.sendMouseEvent(MouseEvent(comp, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), modifiers, awtX, awtY, 1, false, MouseEvent.BUTTON1))
            println("[DEBUG-clickBySelector] ✅ 鼠标事件已发送至 AWT 坐标 ($awtX, $awtY)")
        }
    }

    override fun clickByCoordinates(x: Int, y: Int) {
        println("[DEBUG-clickByCoordinates] 入口: 文档坐标=($x, $y), isDestroyed=${isDestroyed.get()}")
        if (isDestroyed.get()) {
            println("[DEBUG-clickByCoordinates] ❌ 已销毁，直接返回！")
            return
        }

        val jsCode = coordinateTransformJs(x, y)

        println("[DEBUG-clickByCoordinates] 开始执行 JS 坐标转换...")
        evaluateJavaScript(jsCode) { result ->
            println("[DEBUG-clickByCoordinates] JS 回调结果: result='$result'")
            val parts = result.trim('"').split(',')
            println("[DEBUG-clickByCoordinates] 解析 parts: size=${parts.size}, parts=$parts")
            if (parts.size < 4) {
                println("[DEBUG-clickByCoordinates] ❌ 返回格式不正确（需要4个值：clientX,clientY,innerWidth,innerHeight）")
                return@evaluateJavaScript
            }
            val clientX = parts[0].toDoubleOrNull()?.toInt()
            val clientY = parts[1].toDoubleOrNull()?.toInt()
            val innerWidth = parts[2].toDoubleOrNull()
            val innerHeight = parts[3].toDoubleOrNull()
            if (clientX == null || clientY == null || innerWidth == null || innerHeight == null) {
                println("[DEBUG-clickByCoordinates] ❌ 坐标解析为 null！clientX=$clientX, clientY=$clientY, innerWidth=$innerWidth, innerHeight=$innerHeight")
                return@evaluateJavaScript
            }

            val comp = cefBrowser.uiComponent
            if (comp == null) {
                println("[DEBUG-clickByCoordinates] ❌ uiComponent 为 null，无法发送鼠标事件！")
                return@evaluateJavaScript
            }
            val (awtX, awtY) = convertToAwtCoordinates(clientX, clientY, innerWidth, innerHeight, comp)

            println("[DEBUG-clickByCoordinates] CSS视口坐标=($clientX, $clientY), 视口尺寸=${innerWidth}x${innerHeight}, AWT组件尺寸=${comp.width}x${comp.height}, AWT坐标=($awtX, $awtY), zoomLevel=${cefBrowser.zoomLevel}, devicePixelRatio=${java.awt.Toolkit.getDefaultToolkit().screenResolution}, scaleRatio=${comp.width.toDouble()/innerWidth}")

            maskGlassPane.addClickAnimation(awtX, awtY)

            val modifiers = InputEvent.BUTTON1_DOWN_MASK
            println("[DEBUG-clickByCoordinates] ✅ 正在发送 AWT 鼠标事件: MOVED→PRESSED→RELEASED→CLICKED at ($awtX, $awtY)")
            cefBrowser.sendMouseEvent(MouseEvent(comp, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, awtX, awtY, 0, false, MouseEvent.NOBUTTON))
            cefBrowser.sendMouseEvent(MouseEvent(comp, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), modifiers, awtX, awtY, 1, false, MouseEvent.BUTTON1))
            cefBrowser.sendMouseEvent(MouseEvent(comp, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), modifiers, awtX, awtY, 1, false, MouseEvent.BUTTON1))
            cefBrowser.sendMouseEvent(MouseEvent(comp, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), modifiers, awtX, awtY, 1, false, MouseEvent.BUTTON1))
            println("[DEBUG-clickByCoordinates] ✅ 4个鼠标事件已全部发送完毕")
        }
    }

    override fun hoverByCoordinates(x: Int, y: Int) {
        if (isDestroyed.get()) return

        val jsCode = coordinateTransformJs(x, y)

        evaluateJavaScript(jsCode) { result ->
            val parts = result.trim('"').split(',')
            if (parts.size < 4) return@evaluateJavaScript
            val clientX = parts[0].toDoubleOrNull()?.toInt() ?: return@evaluateJavaScript
            val clientY = parts[1].toDoubleOrNull()?.toInt() ?: return@evaluateJavaScript
            val innerWidth = parts[2].toDoubleOrNull() ?: return@evaluateJavaScript
            val innerHeight = parts[3].toDoubleOrNull() ?: return@evaluateJavaScript

            val comp = cefBrowser.uiComponent ?: return@evaluateJavaScript
            val (awtX, awtY) = convertToAwtCoordinates(clientX, clientY, innerWidth, innerHeight, comp)

            maskGlassPane.addHoverAnimation(awtX, awtY)

            cefBrowser.sendMouseEvent(MouseEvent(comp, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, awtX, awtY, 0, false, MouseEvent.NOBUTTON))
        }
    }

    override fun getSemanticSnapshot(callback: (String) -> Unit) {
        val js = """
            (function() {
                function getDirectText(node) {
                    var text = '';
                    for (var i = 0; i < node.childNodes.length; i++) {
                        if (node.childNodes[i].nodeType === 3) {
                            var t = node.childNodes[i].textContent.trim();
                            if (t) text += t + ' ';
                        }
                    }
                    return text.trim();
                }

                var elements = [];
                // 输出 DOM 中所有元素，不做任何业务过滤
                document.querySelectorAll('*').forEach(function(el, index) {
                    var rect = el.getBoundingClientRect();
                    var isVisible = rect.width > 0 && rect.height > 0;
                    var textContent = '';
                    try { textContent = getDirectText(el); } catch(e) { textContent = ''; }
                    if (textContent.length > 200) textContent = textContent.substring(0, 200);
                    var attrs = {};
                    for (var i = 0; i < el.attributes.length; i++) {
                        var attr = el.attributes[i];
                        if (attr.name !== 'class' && attr.name !== 'id' && attr.name !== 'style') {
                            attrs[attr.name] = attr.value.substring(0, 100);
                        }
                    }
                    elements.push({
                        tagName: el.tagName.toLowerCase(),
                        id: el.id || '',
                        className: (el.className && typeof el.className === 'string') ? el.className : '',
                        attributes: attrs,
                        text: textContent,
                        isVisible: isVisible,
                        x: Math.round(rect.left + window.scrollX),
                        y: Math.round(rect.top + window.scrollY),
                        width: Math.round(rect.width),
                        height: Math.round(rect.height),
                        centerX: Math.round(rect.left + window.scrollX + rect.width / 2),
                        centerY: Math.round(rect.top + window.scrollY + rect.height / 2),
                        childCount: el.children.length
                    });
                });

                // iframe 内所有元素
                var iframeInfos = [];
                try {
                    document.querySelectorAll('iframe').forEach(function(iframe, iframeIdx) {
                        var iframeRect = iframe.getBoundingClientRect();
                        iframeInfos.push({
                            src: iframe.src || '',
                            title: iframe.title || '',
                            name: iframe.name || '',
                            isVisible: iframeRect.width > 0 && iframeRect.height > 0,
                            x: Math.round(iframeRect.left + window.scrollX),
                            y: Math.round(iframeRect.top + window.scrollY),
                            width: Math.round(iframeRect.width),
                            height: Math.round(iframeRect.height)
                        });
                        try {
                            var iframeDoc = iframe.contentDocument || iframe.contentWindow.document;
                            iframeDoc.querySelectorAll('*').forEach(function(el, index) {
                                var rect = el.getBoundingClientRect();
                                var isVisible = rect.width > 0 && rect.height > 0;
                                var textContent = '';
                                try { textContent = getDirectText(el); } catch(e) {}
                                if (textContent.length > 200) textContent = textContent.substring(0, 200);
                                var attrs = {};
                                for (var i = 0; i < el.attributes.length; i++) {
                                    var attr = el.attributes[i];
                                    if (attr.name !== 'class' && attr.name !== 'id' && attr.name !== 'style') {
                                        attrs[attr.name] = attr.value.substring(0, 100);
                                    }
                                }
                                elements.push({
                                    tagName: el.tagName.toLowerCase(),
                                    id: el.id || '',
                                    className: (el.className && typeof el.className === 'string') ? el.className : '',
                                    attributes: attrs,
                                    text: textContent,
                                    isVisible: isVisible,
                                    iframeSrc: iframe.src || '',
                                    x: Math.round(rect.left + iframeRect.left + window.scrollX),
                                    y: Math.round(rect.top + iframeRect.top + window.scrollY),
                                    width: Math.round(rect.width),
                                    height: Math.round(rect.height),
                                    centerX: Math.round(rect.left + iframeRect.left + window.scrollX + rect.width / 2),
                                    centerY: Math.round(rect.top + iframeRect.top + window.scrollY + rect.height / 2),
                                    childCount: el.children.length
                                });
                            });
                        } catch(e) {
                            // 跨域 iframe 无法访问内容
                        }
                    });
                } catch(e) {}

                var diag = {
                    url: window.location.href,
                    innerWidth: window.innerWidth,
                    innerHeight: window.innerHeight,
                    scrollX: window.scrollX,
                    scrollY: window.scrollY,
                    documentWidth: document.documentElement.scrollWidth,
                    documentHeight: document.documentElement.scrollHeight,
                    devicePixelRatio: window.devicePixelRatio,
                    totalElements: elements.length,
                    visibleElements: elements.filter(function(e) { return e.isVisible; }).length,
                    hiddenElements: elements.filter(function(e) { return !e.isVisible; }).length,
                    iframeCount: iframeInfos.length
                };
                return JSON.stringify({diagnostics: diag, iframeInfos: iframeInfos, ariaTree: elements});
            })()
        """.trimIndent()
        
        evaluateJavaScript(js) { json ->
            println("[DEBUG-getSemanticSnapshot] 返回长度=${json.length}")
            callback(json)
        }
    }

    override fun getSelectors(callback: (String) -> Unit) {
        val js = """
            (function() {
                function getDirectText(node) {
                    var text = '';
                    for (var i = 0; i < node.childNodes.length; i++) {
                        if (node.childNodes[i].nodeType === 3) {
                            var t = node.childNodes[i].textContent.trim();
                            if (t) text += t + ' ';
                        }
                    }
                    return text.trim();
                }

                function generateUniqueSelector(el) {
                    if (el.id) return '#' + CSS.escape(el.id);
                    var parts = [];
                    var current = el;
                    var depth = 0;
                    while (current && current !== document.documentElement && depth < 5) {
                        depth++;
                        var part = current.tagName.toLowerCase();
                        if (current.id) {
                            parts.unshift('#' + CSS.escape(current.id));
                            break;
                        }
                        if (current.className && typeof current.className === 'string') {
                            var classes = current.className.trim().split(/\s+/).filter(function(c) { return c && !c.startsWith('__'); });
                            if (classes.length > 0) {
                                part += '.' + classes.map(function(c) { return CSS.escape(c); }).join('.');
                            }
                        }
                        var parent = current.parentElement;
                        if (parent) {
                            var siblings = Array.from(parent.children).filter(function(s) { return s.tagName === current.tagName; });
                            if (siblings.length > 1) {
                                var idx = siblings.indexOf(current) + 1;
                                part += ':nth-of-type(' + idx + ')';
                            }
                        }
                        parts.unshift(part);
                        current = current.parentElement;
                    }
                    return parts.join(' > ');
                }

                var selectorsData = [];
                // 输出 DOM 中所有元素的 CSS 选择器，不做任何业务过滤
                document.querySelectorAll('*').forEach(function(el, index) {
                    var rect = el.getBoundingClientRect();
                    var isVisible = rect.width > 0 && rect.height > 0;
                    var textContent = '';
                    try { textContent = getDirectText(el); } catch(e) {}
                    if (textContent.length > 100) textContent = textContent.substring(0, 100);
                    selectorsData.push({
                        selector: generateUniqueSelector(el),
                        tagName: el.tagName.toLowerCase(),
                        id: el.id || '',
                        className: (el.className && typeof el.className === 'string') ? el.className : '',
                        text: textContent,
                        isVisible: isVisible,
                        x: Math.round(rect.left + window.scrollX),
                        y: Math.round(rect.top + window.scrollY),
                        width: Math.round(rect.width),
                        height: Math.round(rect.height),
                        centerX: Math.round(rect.left + window.scrollX + rect.width / 2),
                        centerY: Math.round(rect.top + window.scrollY + rect.height / 2)
                    });
                });

                // iframe 内所有元素的选择器
                try {
                    document.querySelectorAll('iframe').forEach(function(iframe, iframeIdx) {
                        try {
                            var iframeDoc = iframe.contentDocument || iframe.contentWindow.document;
                            var iframeRect = iframe.getBoundingClientRect();
                            iframeDoc.querySelectorAll('*').forEach(function(el, index) {
                                var rect = el.getBoundingClientRect();
                                var isVisible = rect.width > 0 && rect.height > 0;
                                var textContent = '';
                                try { textContent = getDirectText(el); } catch(e) {}
                                if (textContent.length > 100) textContent = textContent.substring(0, 100);
                                selectorsData.push({
                                    selector: generateUniqueSelector(el),
                                    iframeSrc: iframe.src || '',
                                    tagName: el.tagName.toLowerCase(),
                                    id: el.id || '',
                                    className: (el.className && typeof el.className === 'string') ? el.className : '',
                                    text: textContent,
                                    isVisible: isVisible,
                                    x: Math.round(rect.left + iframeRect.left + window.scrollX),
                                    y: Math.round(rect.top + iframeRect.top + window.scrollY),
                                    width: Math.round(rect.width),
                                    height: Math.round(rect.height),
                                    centerX: Math.round(rect.left + iframeRect.left + window.scrollX + rect.width / 2),
                                    centerY: Math.round(rect.top + iframeRect.top + window.scrollY + rect.height / 2)
                                });
                            });
                        } catch(e) {}
                    });
                } catch(e) {}

                var visibleCount = selectorsData.filter(function(s) { return s.isVisible; }).length;
                var hiddenCount = selectorsData.filter(function(s) { return !s.isVisible; }).length;
                return JSON.stringify({total: selectorsData.length, visible: visibleCount, hidden: hiddenCount, selectors: selectorsData});
            })()
        """.trimIndent()
        
        evaluateJavaScript(js) { json ->
            println("[DEBUG-getSelectors] 返回长度=${json.length}")
            callback(json)
        }
    }

    override fun destroy() {
        if (isDestroyed.compareAndSet(false, true)) {
            maskGlassPane.stopAnimationTimer()
            browser.dispose()
        }
    }

    override fun setMaskEnabled(enabled: Boolean) {
        maskGlassPane.setActive(enabled)
    }
}

/**
 * AWT 遮罩 Glass Pane：盖在浏览器组件上面
 * - 激活时：半透明、吞噬所有鼠标事件、渲染动画
 * - 关闭时：完全透明、不吞噬事件
 * - 程序代码的 cefBrowser.sendMouseEvent() 直达CEF组件，绕过此层
 */
class MaskGlassPane : javax.swing.JPanel() {
    private var active = false
    
    // 动画事件列表
    private val animations = java.util.concurrent.CopyOnWriteArrayList<AnimEvent>()
    
    // 动画刷新定时器 (约60fps)
    private val timer = javax.swing.Timer(16, java.awt.event.ActionListener {
        val now = System.currentTimeMillis()
        // 移除已完成的动画
        animations.removeAll { anim -> now - anim.startTime >= anim.duration }
        repaint()
    })
    
    data class AnimEvent(
        val type: AnimType,
        val x: Int,
        val y: Int,
        val startTime: Long,
        val duration: Long
    )
    
    enum class AnimType { CLICK, HOVER }
    
    init {
        isOpaque = false
        isFocusable = false
        
        // 吞噬所有鼠标事件
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { if (active) consume(e) }
            override fun mousePressed(e: java.awt.event.MouseEvent) { if (active) consume(e) }
            override fun mouseReleased(e: java.awt.event.MouseEvent) { if (active) consume(e) }
            override fun mouseEntered(e: java.awt.event.MouseEvent) { if (active) consume(e) }
            override fun mouseExited(e: java.awt.event.MouseEvent) { if (active) consume(e) }
        })
        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) { if (active) consume(e) }
            override fun mouseDragged(e: java.awt.event.MouseEvent) { if (active) consume(e) }
        })
    }
    
    private fun consume(e: java.awt.event.MouseEvent) {
        e.consume()
    }
    
    fun setActive(enabled: Boolean) {
        active = enabled
        if (enabled) {
            timer.start()
        } else {
            timer.stop()
            animations.clear()
        }
        repaint()
    }
    
    fun addClickAnimation(x: Int, y: Int) {
        if (!active) return
        animations.add(AnimEvent(AnimType.CLICK, x, y, System.currentTimeMillis(), 600))
    }
    
    fun addHoverAnimation(x: Int, y: Int) {
        if (!active) return
        animations.add(AnimEvent(AnimType.HOVER, x, y, System.currentTimeMillis(), 800))
    }
    
    fun stopAnimationTimer() {
        timer.stop()
    }
    
    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        if (!active) return
        
        val g2d = g as java.awt.Graphics2D
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        
        // 半透明遮罩底色 (5% 黑色)
        g2d.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.05f)
        g2d.color = java.awt.Color.BLACK
        g2d.fillRect(0, 0, width, height)
        
        // 边框 (橙色虚线，标识自动化模式)
        g2d.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.5f)
        g2d.color = java.awt.Color(255, 69, 0) // #FF4500
        g2d.stroke = java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 10f, floatArrayOf(10f), 0f)
        g2d.drawRect(2, 2, width - 4, height - 4)
        
        // 右上角标签 "AUTO MODE"
        g2d.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.7f)
        g2d.color = java.awt.Color(255, 69, 0)
        val font = g2d.font.deriveFont(java.awt.Font.BOLD, 13f)
        g2d.font = font
        val label = "AUTO MODE"
        val fm = g2d.fontMetrics
        val labelWidth = fm.stringWidth(label)
        val labelHeight = fm.height
        val labelX = width - labelWidth - 12
        val labelY = 14 + labelHeight / 2
        // 背景
        g2d.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.6f)
        g2d.color = java.awt.Color(30, 30, 30)
        g2d.fillRoundRect(labelX - 6, 4, labelWidth + 12, labelHeight + 8, 6, 6)
        // 文字
        g2d.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.9f)
        g2d.color = java.awt.Color(255, 69, 0)
        g2d.drawString(label, labelX, labelY)
        
        // 绘制动画
        val now = System.currentTimeMillis()
        for (anim in animations) {
            val progress = ((now - anim.startTime).toFloat() / anim.duration.toFloat()).coerceIn(0f, 1f)
            val alpha = (1f - progress)
            
            when (anim.type) {
                AnimType.CLICK -> drawClickRipple(g2d, anim.x, anim.y, progress, alpha)
                AnimType.HOVER -> drawHoverPulse(g2d, anim.x, anim.y, progress, alpha)
            }
        }
    }
    
    private fun drawClickRipple(g2d: java.awt.Graphics2D, x: Int, y: Int, progress: Float, alpha: Float) {
        // 中心点
        g2d.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha * 0.95f)
        g2d.color = java.awt.Color(255, 69, 0)
        g2d.fillOval(x - 6, y - 6, 12, 12)
        
        // 内圈涟漪
        val innerR = (14 + progress * 22).toInt()
        g2d.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha * 0.5f)
        g2d.color = java.awt.Color(255, 69, 0)
        g2d.stroke = java.awt.BasicStroke(3f)
        g2d.drawOval(x - innerR, y - innerR, innerR * 2, innerR * 2)
        
        // 外圈涟漪
        val outerR = (18 + progress * 30).toInt()
        g2d.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha * 0.35f)
        g2d.color = java.awt.Color.WHITE
        g2d.stroke = java.awt.BasicStroke(2f)
        g2d.drawOval(x - outerR, y - outerR, outerR * 2, outerR * 2)
    }
    
    private fun drawHoverPulse(g2d: java.awt.Graphics2D, x: Int, y: Int, progress: Float, alpha: Float) {
        val r = (10 + progress * 10).toInt()
        g2d.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha * 0.6f)
        g2d.color = java.awt.Color(0, 191, 255)
        g2d.fillOval(x - r, y - r, r * 2, r * 2)
        
        g2d.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha * 0.8f)
        g2d.color = java.awt.Color.WHITE
        g2d.fillOval(x - 3, y - 3, 6, 6)
    }
}

@Composable
actual fun WebView(
    controller: WebViewController,
    modifier: Modifier
) {
    val jvmController = controller as? JvmWebViewController ?: return

    SwingPanel(
        factory = {
            // 用 JLayeredPane 包装浏览器组件和遮罩 Glass Pane
            val layeredPane = javax.swing.JLayeredPane()
            val browserComp = jvmController.browser.getComponent()
            val glassPane = jvmController.maskGlassPane
            
// 浏览器在底层
            layeredPane.add(browserComp, javax.swing.JLayeredPane.DEFAULT_LAYER as Int)
            // 遮罩在上层
            layeredPane.add(glassPane, javax.swing.JLayeredPane.PALETTE_LAYER as Int)
            
            // 响应尺寸变化
            layeredPane.addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    val w = layeredPane.width
                    val h = layeredPane.height
                    browserComp.setBounds(0, 0, w, h)
                    glassPane.setBounds(0, 0, w, h)
                }
            })
            
            layeredPane
        },
        modifier = modifier
    )
}

@Composable
actual fun rememberWebViewController(initialUrl: String, isOsr: Boolean): WebViewController {
    val controller = androidx.compose.runtime.remember(initialUrl, isOsr) { JvmWebViewController(initialUrl, isOsr) }
    androidx.compose.runtime.DisposableEffect(controller) {
        onDispose {
            controller.destroy()
        }
    }
    return controller
}
