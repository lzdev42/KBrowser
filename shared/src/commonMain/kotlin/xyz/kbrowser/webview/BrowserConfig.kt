package xyz.kbrowser.webview

data class BrowserConfig(
    val storageDir: String,
    val userAgent: String? = null,
    val isHeadless: Boolean = false
)
