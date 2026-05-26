package xyz.kbrowser.jcef

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import java.util.Base64

class KBErrorPageHandler : CefLoadHandlerAdapter() {
    override fun onLoadError(b: CefBrowser, f: CefFrame, errorCode: CefLoadHandler.ErrorCode, errorText: String, failedUrl: String) {
        if (errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return
        val html = "<html><body><h2>网页加载失败</h2><p>${errorText}</p><p>URL: ${failedUrl}</p></body></html>"
        val encoded = Base64.getEncoder().encodeToString(html.toByteArray())
        b.loadURL("data:text/html;charset=utf-8;base64,$encoded")
    }
}
