package xyz.kbrowser.webview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object JcefChecker {
    val isJcefAvailable: Boolean by lazy {
        try {
            // 尝试加载 JBR 特有的 JCefAppConfig 以及 JCEF 的 CefApp
            Class.forName("com.jetbrains.cef.JCefAppConfig")
            Class.forName("org.cef.CefApp")
            true
        } catch (e: Throwable) {
            false
        }
    }
}

class FallbackWebView(initialUrl: String) : KBWebView {
    override val currentUrl = MutableStateFlow<String?>(initialUrl)
    override val currentTitle = MutableStateFlow<String?>(null)
    override val loadingState = MutableStateFlow<LoadingState>(LoadingState.Finished)
    override val progress = MutableStateFlow(1f)
    override val canGoBack = MutableStateFlow(false)
    override val canGoForward = MutableStateFlow(false)

    override fun loadUrl(url: String) {
        currentUrl.value = url
    }

    override fun loadHtml(html: String) {}

    override fun reload() {}

    override fun stopLoading() {}

    override fun goBack() {}

    override fun goForward() {}

    override fun evaluateJavascript(script: String, callback: ((String) -> Unit)?) {}

    override fun registerJsCallback(name: String, callback: (String) -> Unit) {}

    override fun unregisterJsCallback(name: String) {}

    override fun registerJsHandler(name: String, handler: (String) -> String) {}

    override fun unregisterJsHandler(name: String) {}

    override fun clearCacheAndCookies() {}

    override fun setWebViewClient(client: KBWebViewClient?) {}

    override fun setWebChromeClient(client: KBWebChromeClient?) {}

    override fun destroy() {}
    override fun setInteractionLocked(locked: Boolean) {}
    override fun updateMouseTrail(viewportX: Int, viewportY: Int) {}
    override var onNewWindowRequest: ((url: String) -> Unit)? = null

    override var onFileDialogRequest: ((request: KBFileDialogRequest, callback: KBFileDialogCallback) -> Unit)? = null

    override suspend fun takeScreenshot(): ByteArray? = null
}
