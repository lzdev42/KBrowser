package xyz.kbrowser.jcef

import org.cef.network.CefCookie
import org.cef.network.CefCookieManager

class KBCefCookieManager(private val manager: CefCookieManager) {
    fun setCookie(url: String, cookie: CefCookie): Boolean {
        return manager.setCookie(url, cookie)
    }
    
    fun deleteCookies(url: String, cookieName: String): Boolean {
        return manager.deleteCookies(url, cookieName)
    }
}
