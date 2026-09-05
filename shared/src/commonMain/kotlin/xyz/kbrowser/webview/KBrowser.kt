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
     * Creates a page.
     *
     * - Without a viewport: the page is meant to be mounted in a [KBWebView] Composable for
     *   display; its render size is determined by the Compose modifier.
     * - With a viewport (e.g. 1280×720): the page attaches no UI and renders at exactly the
     *   viewport size, for background automation (screenshots, CDP, AX tree). JCEF OSR is
     *   off-screen rendering by nature, so no window is needed to host the page.
     */
    suspend fun newPage(
        profile: KBProfile? = null,
        viewportWidth: Int? = null,
        viewportHeight: Int? = null
    ): KBPage {
        storageDir
            ?: throw IllegalStateException("KBrowser.initializeConfig() must be called before newPage()")
        val webView = withContext(Dispatchers.Main) {
            createPageWebView(null, profile, viewportWidth, viewportHeight)
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
