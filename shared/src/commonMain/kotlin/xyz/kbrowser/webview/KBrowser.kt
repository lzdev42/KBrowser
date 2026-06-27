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

    // 全局唯一初始化入口
    fun initializeConfig(storageDir: String?, useOsr: Boolean = true) {
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

    /**
     * 创建 UI 模式的 Page：可挂载到 KBWebView Composable 显示。
     * 渲染尺寸由 Compose 的 modifier 决定。
     *
     * 导航请用 [KBPage.loadUrl]：`val page = KBrowser.newPage(); page.loadUrl(url)`。
     *
     * 使用场景：需要在应用窗口中显示网页内容。
     */
    suspend fun newPage(): KBPage =
        baseNewPage(viewportWidth = null, viewportHeight = null, headless = false)

    /**
     * 创建无头模式的后台自动化 Page：不挂任何 UI，渲染尺寸由透明 JFrame 决定。
     *
     * 导航请用 [KBPage.loadUrl]：`val page = KBrowser.newHeadlessTab(); page.loadUrl(url)`。
     *
     * 使用场景：纯自动化任务（截图、CDP 操作、AX Tree 提取等）。
     * **禁止**将返回的 page 挂载到 KBWebView Composable —— 会导致尺寸异常。
     *
     * 默认 viewport 1280×720，与 Playwright 默认值一致。
     */
    suspend fun newHeadlessTab(
        viewportWidth: Int = 1280,
        viewportHeight: Int = 720
    ): KBPage = baseNewPage(viewportWidth, viewportHeight, headless = true)

    /**
     * @Deprecated 使用 [newPage]（UI 模式）或 [newHeadlessTab]（无头自动化）。
     *
     * 旧 API 把"创建 page"和"加载 url"混在一个 suspend 函数里，且 url 加载是
     * fire-and-forget（GlobalScope.launch），与 suspend 语义不符。创建与导航
     * 应分离：newPage/newHeadlessTab 只创建，导航用 [KBPage.loadUrl]。
     */
    @Deprecated(
        "Use newPage() or newHeadlessTab() to create, then page.loadUrl(url) to navigate",
        ReplaceWith("if (headless) newHeadlessTab(viewportWidth ?: 1280, viewportHeight ?: 720).also { if (url != null) it.loadUrl(url) } else newPage().also { if (url != null) it.loadUrl(url) }")
    )
    suspend fun newPage(
        url: String? = null,
        viewportWidth: Int? = null,
        viewportHeight: Int? = null,
        headless: Boolean = true
    ): KBPage {
        val page = baseNewPage(viewportWidth, viewportHeight, headless)
        if (url != null) page.loadUrl(url)
        return page
    }

    private suspend fun baseNewPage(
        viewportWidth: Int?,
        viewportHeight: Int?,
        headless: Boolean
    ): KBPage {
        val path = storageDir
            ?: throw IllegalStateException("KBrowser.initializeConfig() must be called before newPage()")
        val webView = withContext(Dispatchers.Main) {
            createHeadlessWebView(null, null, viewportWidth, viewportHeight, headless)
        }
        val page = KBPage(webView)
        _pages.update { it + page }
        return page
    }

    fun getPages(): List<KBPage> = _pages.value

    /**
     * @Deprecated newPage/newHeadlessTab 已自动注册，无需手动调用。
     * 仅保留供需要绕过工厂方法、手动构造 JvmWebView 的特殊场景（如自定义容器测试）使用。
     */
    @Deprecated("newPage/newHeadlessTab 已自动注册，无需手动调用", level = DeprecationLevel.WARNING)
    fun registerPage(page: KBPage) {
        _pages.update { if (it.contains(page)) it else it + page }
    }

    /**
     * @Deprecated 通常配合 [registerPage] 使用，正常路径请用 KBPage.close()。
     */
    @Deprecated("通常配合 registerPage 使用，正常路径请用 KBPage.close()", level = DeprecationLevel.WARNING)
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
