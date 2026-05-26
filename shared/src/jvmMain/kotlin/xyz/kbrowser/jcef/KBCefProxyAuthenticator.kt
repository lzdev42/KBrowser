package xyz.kbrowser.jcef

import org.cef.browser.CefBrowser
import org.cef.callback.CefAuthCallback
import org.cef.handler.CefRequestHandlerAdapter

class KBCefProxyAuthenticator : CefRequestHandlerAdapter() {
    override fun getAuthCredentials(
        browser: CefBrowser, originUrl: String, isProxy: Boolean, host: String, 
        port: Int, realm: String, scheme: String, callback: CefAuthCallback
    ): Boolean {
        if (!isProxy) return false
        // TODO: Retrieve credentials from global configuration here when available
        return false 
    }
}
