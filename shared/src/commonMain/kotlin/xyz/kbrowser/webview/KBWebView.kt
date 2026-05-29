package xyz.kbrowser.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

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
    fun registerJsCallback(name: String, callback: (String) -> Unit)
    fun unregisterJsCallback(name: String)

    // 会话生命周期与清理
    fun clearCacheAndCookies()

    // 回调代理设置
    fun setWebViewClient(client: KBWebViewClient?)
    fun setWebChromeClient(client: KBWebChromeClient?)

    // 释放资源
    fun destroy()

    // 网页截图
    suspend fun takeScreenshot(): ByteArray?

    /**
     * 锁定/解锁用户交互。
     * locked=true 时在浏览器组件上覆盖 AWT 拦截层，阻止用户鼠标/键盘输入。
     * 自动化操作（CDP）不受影响。
     * 目前仅 JVM 平台有效，Android/iOS 为空实现。
     */
    fun setInteractionLocked(locked: Boolean)

    /**
     * 更新鼠标轨迹位置（在锁定状态下显示自动化操作的光标动画）。
     * 坐标为视口坐标（CSS 像素）。仅 JVM 平台有效。
     */
    fun updateMouseTrail(viewportX: Int, viewportY: Int)

    /**
     * 新窗口/新标签页请求回调。
     * 当页面通过 target="_blank"、window.open() 等方式请求打开新窗口时触发。
     * 设置此回调后，默认的弹窗行为会被阻止，URL 交由调用方处理。
     * 不设置时，新窗口请求会被静默丢弃（不会打开任何东西）。
     */
    var onNewWindowRequest: ((url: String) -> Unit)?
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
    profile: KBProfile? = null
): KBWebView

internal expect suspend fun performClickByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int
)

internal expect suspend fun performHoverByCoordinates(
    webView: KBWebView,
    x: Int,
    y: Int
)

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
