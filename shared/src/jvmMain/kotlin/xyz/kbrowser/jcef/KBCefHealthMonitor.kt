package xyz.kbrowser.jcef

import org.cef.browser.CefBrowser
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import javax.swing.SwingUtilities

class KBCefHealthMonitor : CefRequestHandlerAdapter() {
    override fun onRenderProcessTerminated(
        browser: CefBrowser, 
        status: CefRequestHandler.TerminationStatus, 
        errorCode: Int, 
        errorString: String?
    ) {
        SwingUtilities.invokeLater {
            browser.reload()
        }
    }
}
