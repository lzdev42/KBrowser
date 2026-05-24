package xyz.kbrowser.webview

interface KBWebViewClient {
    fun onPageStarted(url: String)
    fun onPageFinished(url: String)
    fun onReceivedError(error: Diagnostics)
}
