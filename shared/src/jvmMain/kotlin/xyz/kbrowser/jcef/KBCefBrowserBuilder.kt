package xyz.kbrowser.jcef

import org.cef.browser.CefBrowser

class KBCefBrowserBuilder {
    var myUrl: String = "about:blank"
    var myClient: KBCefClient? = null
    var myCefBrowser: CefBrowser? = null
    var myIsOffScreenRendering: Boolean = false
    var myEnableOpenDevToolsMenuItem: Boolean = true
    var myCreateImmediately: Boolean = false
    var myWindowlessFrameRate: Int = 30
    var myMouseWheelEventEnable: Boolean = true

    fun setUrl(url: String): KBCefBrowserBuilder {
        myUrl = url
        return this
    }

    fun setClient(client: KBCefClient): KBCefBrowserBuilder {
        myClient = client
        return this
    }

    fun setOffScreenRendering(offScreen: Boolean): KBCefBrowserBuilder {
        myIsOffScreenRendering = offScreen
        return this
    }

    fun build(): KBCefBrowser {
        return KBCefBrowser(this)
    }
}
