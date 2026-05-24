package xyz.kbrowser.webview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object KBrowser {
    var config: BrowserConfig = BrowserConfig()
        private set

    private val _pages = MutableStateFlow<List<KBPage>>(emptyList())
    val pages: StateFlow<List<KBPage>> = _pages.asStateFlow()

    fun configure(config: BrowserConfig) {
        this.config = config
    }

    suspend fun newPage(url: String? = null, profile: KBProfile? = null, isOsr: Boolean = false): KBPage {
        val webView = withContext(Dispatchers.Default) {
            createHeadlessWebView(null, profile, isOsr)
        }
        val page = KBPage(webView)
        _pages.update { it + page }
        if (url != null) {
            // 不要在这里挂起等待加载完成，否则会出现死锁：
            // loadUrl 等待页面加载 -> JCEF 等待组件被挂载到界面上才开始加载 -> 界面等待 newPage 返回才去挂载
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                try {
                    page.loadUrl(url)
                } catch (e: Exception) {
                    println("[KBrowser] Initial loadUrl failed: ${e.message}")
                }
            }
        }
        return page
    }

    fun getPages(): List<KBPage> {
        return _pages.value
    }

    fun registerPage(page: KBPage) {
        _pages.update { if (it.contains(page)) it else it + page }
    }

    fun unregisterPage(page: KBPage) {
        _pages.update { it - page }
    }

    fun shutdown() {
        _pages.value.forEach { it.close() }
        _pages.value = emptyList()
        performGlobalShutdown()
    }
}

internal expect fun performGlobalShutdown()
