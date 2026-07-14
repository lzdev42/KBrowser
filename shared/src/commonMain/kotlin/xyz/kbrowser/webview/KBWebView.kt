package xyz.kbrowser.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import xyz.kbrowser.webview.debug.KBDebug

interface KBWebView {
    // 响应式 Compose 状态
    val currentUrl: StateFlow<String?>
    val currentTitle: StateFlow<String?>
    val loadingState: StateFlow<LoadingState>
    val progress: StateFlow<Float> // 0.0f ~ 1.0f
    val canGoBack: StateFlow<Boolean>
    val canGoForward: StateFlow<Boolean>

    // 基础导航控制
    fun loadUrl(url: String)
    fun loadHtml(html: String)
    fun reload()
    fun stopLoading()
    fun goBack()
    fun goForward()

    // JS <-> Native 交互与通信
    fun evaluateJavascript(script: String, callback: ((String) -> Unit)? = null)

    /**
     * 注册单向通知回调（Fire-and-Forget）。
     * JS 端调用 window.[name](data)，Native 收到 data 字符串，无返回值。
     *
     * 适用场景：埋点上报、事件通知、日志等不需要等待结果的场景。
     *
     * JS 调用方式：
     * ```javascript
     * window.onUserAction("click");
     * ```
     */
    fun registerJsCallback(name: String, callback: (String) -> Unit)
    fun unregisterJsCallback(name: String)

    /**
     * 注册支持 Promise 的双向请求处理器（Request-Response）。
     * JS 端 await window.[name](data) 可直接拿到 Kotlin handler 的返回值。
     *
     * 适用场景：JS 向 Native 请求数据、配置、计算结果等需要等待响应的场景。
     *
     * Kotlin 注册：
     * ```kotlin
     * webView.registerJsHandler("getConfig") { jsonString ->
     *     """{"theme":"dark","version":"1.0"}"""
     * }
     * ```
     *
     * JS 调用（支持 async/await）：
     * ```javascript
     * const config = await window.getConfig(JSON.stringify({ key: "theme" }));
     * console.log(JSON.parse(config).theme); // "dark"
     * ```
     *
     * 注意：handler 在后台线程执行，不要在其中直接操作 UI。
     * 如需注销，调用 [unregisterJsHandler]。
     */
    fun registerJsHandler(name: String, handler: (String) -> String)
    fun unregisterJsHandler(name: String)

    // 会话生命周期与清理
    fun clearCacheAndCookies()

    // 回调代理设置
    fun setWebViewClient(client: KBWebViewClient?)
    fun setWebChromeClient(client: KBWebChromeClient?)

    // 释放资源
    fun destroy()

    // 调试 API（统一事件流、健康快照、CDP 逃生舱口）
    val debug: KBDebug

    // 网页截图，截图像素与 CSS 坐标 1:1 对齐
    suspend fun takeScreenshot(): KBScreenshot?

    /**
     * 新窗口/新标签页请求回调。
     * 当页面通过 target="_blank"、window.open() 等方式请求打开新窗口时触发。
     * 设置此回调后，默认的弹窗行为会被阻止，URL 交由调用方处理。
     * 不设置时，新窗口请求会被静默丢弃（不会打开任何东西）。
     */
    var onNewWindowRequest: ((url: String) -> Unit)?

    /**
     * 文件对话框请求回调。
     * 当页面通过 <input type="file"> 或上传按钮触发文件选择时调用。
     *
     * JVM Desktop: 设置后文件选择交由调用方通过 callback 返回文件路径；
     * 不设置时静默取消（不弹原生对话框，OSR 模式下无法弹出）。
     *
     * Android/iOS: 空实现属性，文件上传走平台原生流程。
     */
    var onFileDialogRequest: ((request: KBFileDialogRequest, callback: KBFileDialogCallback) -> Unit)?
}

@Composable
expect fun KBWebView(
    webView: KBWebView,
    modifier: Modifier = Modifier
)

@Composable
expect fun rememberKBWebView(
    initialUrl: String? = null,
    profile: KBProfile? = null
): KBWebView

internal expect fun createHeadlessWebView(
    initialUrl: String? = null,
    profile: KBProfile? = null,
    viewportWidth: Int? = null,
    viewportHeight: Int? = null,
    headless: Boolean = true
): KBWebView

internal expect suspend fun performClickByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    popupSelector: String? = null
): Pair<Int, Int>?

internal expect suspend fun performHoverByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    popupSelector: String? = null
)

internal expect suspend fun verifyElementAtCdp(
    webView: KBWebView,
    vx: Int,
    vy: Int
): String?

internal expect suspend fun performScrollByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int,
    deltaX: Int,
    deltaY: Int
)

internal expect suspend fun performDragByCoordinates(
    webView: KBWebView,
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int
)

internal expect suspend fun performClickByJs(
    webView: KBWebView,
    selector: String
)

internal expect suspend fun performHoverByJs(
    webView: KBWebView,
    selector: String
)

internal expect suspend fun performScrollByJs(
    webView: KBWebView,
    selector: String,
    deltaX: Int,
    deltaY: Int
)

internal expect suspend fun performDragByJs(
    webView: KBWebView,
    startSelector: String,
    endSelector: String
)

internal expect suspend fun performFocusByJs(
    webView: KBWebView,
    selector: String
)

internal expect suspend fun performKeyPress(
    webView: KBWebView,
    key: KeyboardKey
)

internal expect suspend fun performKeyCombination(
    webView: KBWebView,
    modifier: KeyboardKey,
    key: KeyboardKey
)

internal expect suspend fun performTypeChar(
    webView: KBWebView,
    char: Char
)

/**
 * Set files on an input[type=file] element directly via CDP DOM.setFileInputFiles.
 * This bypasses the file dialog entirely — no click, no dialog, no user gesture needed.
 *
 * JVM: uses CDP DOM.setFileInputFiles + dispatches 'change' event.
 * Android/iOS: throws UnsupportedOperationException (mobile uses native file dialog).
 */
internal expect suspend fun performSetFiles(
    webView: KBWebView,
    selector: String,
    filePaths: List<String>
)
