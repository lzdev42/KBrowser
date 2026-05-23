package xyz.kbrowser.webview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class IosWebViewController : WebViewController {
    override val currentUrl = MutableStateFlow<String?>(null)
    override val currentTitle = MutableStateFlow<String?>(null)
    override val loadingState = MutableStateFlow<LoadingState>(LoadingState.Initializing)
    override val progress = MutableStateFlow(0f)
    override val canGoBack = MutableStateFlow(false)
    override val canGoForward = MutableStateFlow(false)

    override fun loadUrl(url: String) {}
    override fun loadHtml(html: String, baseUrl: String) {}
    override fun reload() {}
    override fun stopLoading() {}
    override fun goBack() {}
    override fun goForward() {}
    override fun evaluateJavaScript(script: String, callback: ((String) -> Unit)?) {}
    override fun registerJsCallback(name: String, callback: (String) -> String) {}
    override fun unregisterJsCallback(name: String) {}
    override fun clearCacheAndCookies() {}

    override fun getOuterHtml(callback: (String) -> Unit) {}
    override fun clickBySelector(selector: String) {}
    override fun clickByCoordinates(x: Int, y: Int) {}
    override fun hoverByCoordinates(x: Int, y: Int) {}
    override fun getSemanticSnapshot(callback: (String) -> Unit) {}

    override fun destroy() {}
}

@Composable
actual fun WebView(
    controller: WebViewController,
    modifier: Modifier
) {
    // Stub implementation for iOS
    Box(modifier = modifier.background(Color.LightGray))
}

@Composable
actual fun rememberWebViewController(initialUrl: String, isOsr: Boolean): WebViewController {
    return androidx.compose.runtime.remember { IosWebViewController() }
}
