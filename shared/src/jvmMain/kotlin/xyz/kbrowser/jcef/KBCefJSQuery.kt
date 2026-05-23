package xyz.kbrowser.jcef

import java.util.UUID

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter

/**
 * Ported from IntelliJ IDEA's KBCefJSQuery.
 */
class KBCefJSQuery(val jbCefBrowser: KBCefBrowser) {
    private val funcName = "_q_" + UUID.randomUUID().toString().replace("-", "")
    private val myCefMessageRouter: CefMessageRouter

    init {
        val config = CefMessageRouter.CefMessageRouterConfig()
        config.jsQueryFunction = funcName
        config.jsCancelFunction = funcName + "_cancel"
        myCefMessageRouter = CefMessageRouter.create(config)
        jbCefBrowser.myCefClient.cefClient.addMessageRouter(myCefMessageRouter)
    }

    fun addHandler(handler: (String) -> String?) {
        myCefMessageRouter.addHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(
                browser: CefBrowser,
                frame: CefFrame,
                query_id: Long,
                request: String,
                persistent: Boolean,
                callback: CefQueryCallback
            ): Boolean {
                val response = handler(request)
                if (response != null) {
                    callback.success(response)
                } else {
                    callback.failure(1, "Handler returned null")
                }
                return true
            }
        }, false)
    }

    fun inject(request: String): String {
        return "window.$funcName({request: $request, onSuccess: function(r){}, onFailure: function(e,m){}});"
    }
}
