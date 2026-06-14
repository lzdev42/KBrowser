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
    internal var storageDir: String? = null
        private set
    internal var useOsrMode: Boolean = true
        private set

    internal fun getConfigPath(): String? = storageDir

    // 全局唯一初始化入口
    fun initializeConfig(storageDir: String, useOsr: Boolean = true) {
        this.storageDir = storageDir
        this.useOsrMode = useOsr
    }

    private val _pages = MutableStateFlow<List<KBPage>>(emptyList())
    val pages: StateFlow<List<KBPage>> = _pages.asStateFlow()

    /**
     * Sets the root directory for KBrowser data storage.
     * Must be called before [initializeKBrowser] and [newPage].
     *
     * KBrowser will create a `kbrowser_profile` subdirectory inside [path]
     * for its own browser data (cookies, cache, etc.).
     *
     * Example:
     * ```kotlin
     * KBrowser.setConfigPath("/path/to/myapp/cache")
     * initializeKBrowser()
     * ```
     */
    @Deprecated("Use initializeConfig instead", ReplaceWith("initializeConfig(path)"))
    fun setConfigPath(path: String) {
        initializeConfig(path)
    }

    suspend fun newPage(url: String? = null): KBPage {
        val path = storageDir
            ?: throw IllegalStateException("KBrowser.initializeConfig() must be called before newPage()")
        val webView = withContext(Dispatchers.Main) {
            createHeadlessWebView(null, null)
        }
        val page = KBPage(webView)
        _pages.update { it + page }
        if (url != null) {
            GlobalScope.launch(Dispatchers.Main) {
                try {
                    page.loadUrl(url)
                } catch (e: Exception) {
                    println("[KBrowser] Initial loadUrl failed: ${e.message}")
                }
            }
        }
        return page
    }

    fun getPages(): List<KBPage> = _pages.value

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

internal expect suspend fun fetchAxTreeNative(webView: KBWebView): AxTreeData?

internal expect suspend fun findElementsNative(
    webView: KBWebView,
    selector: String,
    selectorType: KBSelectorType,
    name: String?,
    exact: Boolean
): List<LocateResult>?
