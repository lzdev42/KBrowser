package xyz.kbrowser.webview

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class VisualIndicatorEvent(
    val type: IndicatorType,
    val x: Float,
    val y: Float,
    val id: Long = kotlin.random.Random.nextLong()
)

enum class IndicatorType {
    CLICK, HOVER
}

interface WebViewController {
    // 状态观测 (使用 Flow 保证响应式)
    val currentUrl: StateFlow<String?>
    val currentTitle: StateFlow<String?>
    val loadingState: StateFlow<LoadingState>
    val progress: StateFlow<Float> // 0.0f - 1.0f
    
    val canGoBack: StateFlow<Boolean>
    val canGoForward: StateFlow<Boolean>
    
    // 页面控制 API
    fun loadUrl(url: String)
    fun loadHtml(html: String, baseUrl: String = "about:blank")
    fun reload()
    fun stopLoading()
    fun goBack()
    fun goForward()

    // 脚本与桥接 API
    fun evaluateJavaScript(script: String, callback: ((String) -> Unit)? = null)
    fun registerJsCallback(name: String, callback: (String) -> String)
    fun unregisterJsCallback(name: String)
    
    // 数据清理 API
    fun clearCacheAndCookies()

    // 轻量级自动化 API
    fun getOuterHtml(callback: (String) -> Unit)
    fun getSelectors(callback: (String) -> Unit)
    fun clickBySelector(selector: String)
    fun clickByCoordinates(x: Int, y: Int)
    fun hoverByCoordinates(x: Int, y: Int)
    fun getSemanticSnapshot(callback: (String) -> Unit)
    
    // 遮罩层控制
    fun setMaskEnabled(enabled: Boolean)
    
    // 销毁释放
    fun destroy()
}
