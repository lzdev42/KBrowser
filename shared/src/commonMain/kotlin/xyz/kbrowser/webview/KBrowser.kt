package xyz.kbrowser.webview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

object KBrowser {
    internal var storageDir: String? = null
        private set
    internal var useOsrMode: Boolean = true
        private set

    internal fun getConfigPath(): String? = storageDir

    fun initializeConfig(storageDir: String?, useOsr: Boolean = true) {
        this.storageDir = storageDir
        this.useOsrMode = useOsr
    }

    private val _pages = MutableStateFlow<List<KBPage>>(emptyList())
    val pages: StateFlow<List<KBPage>> = _pages.asStateFlow()

    /**
     * 创建 UI 模式的 Page：可挂载到 KBWebView Composable 显示。
     * 渲染尺寸由 Compose 的 modifier 决定。导航用 [KBPage.loadUrl]。
     */
    suspend fun newPage(profile: KBProfile? = null): KBPage =
        baseNewPage(profile, viewportWidth = null, viewportHeight = null, headless = false)

    /**
     * 创建无头模式的后台自动化 Page：不挂任何 UI，渲染尺寸由透明 JFrame 决定。
     * **禁止**将返回的 page 挂载到 KBWebView Composable —— 会导致尺寸异常。
     * 默认 viewport 1280×720，与 Playwright 默认值一致。导航用 [KBPage.loadUrl]。
     */
    suspend fun newHeadlessTab(
        profile: KBProfile? = null,
        viewportWidth: Int = 1280,
        viewportHeight: Int = 720
    ): KBPage = baseNewPage(profile, viewportWidth, viewportHeight, headless = true)

    private suspend fun baseNewPage(
        profile: KBProfile?,
        viewportWidth: Int?,
        viewportHeight: Int?,
        headless: Boolean
    ): KBPage {
        storageDir
            ?: throw IllegalStateException("KBrowser.initializeConfig() must be called before newPage()")
        val webView = withContext(Dispatchers.Main) {
            createHeadlessWebView(null, profile, viewportWidth, viewportHeight, headless)
        }
        val page = KBPage(webView)
        _pages.update { it + page }
        return page
    }

    fun getPages(): List<KBPage> = _pages.value

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
