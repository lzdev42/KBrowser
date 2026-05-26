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
    isOsr: Boolean = false
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
