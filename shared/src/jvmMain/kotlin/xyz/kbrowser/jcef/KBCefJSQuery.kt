package xyz.kbrowser.jcef

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandler
import org.cef.handler.CefMessageRouterHandlerAdapter
import java.util.Collections
import java.util.HashMap
import java.util.concurrent.atomic.AtomicBoolean

class KBCefJSQuery private constructor(
    private val myBrowser: KBCefBrowserBase,
    val myFunc: JSQueryFunc
) : KBCefDisposable {
    private val myDisposeHelper = AtomicBoolean(false)
    private val myHandlerMap = Collections.synchronizedMap(HashMap<((String) -> Response), CefMessageRouterHandler>())

    class JSQueryFunc(client: KBCefClient, index: Int) {
        val myFuncName = "cefQuery_${client.hashCode().toString().replace("-", "_")}_slot_$index"
        val myRouter: CefMessageRouter
        init {
            val config = CefMessageRouter.CefMessageRouterConfig()
            config.jsQueryFunction = myFuncName
            config.jsCancelFunction = "cefQuery_cancel_${client.hashCode().toString().replace("-", "_")}_slot_$index"
            myRouter = CefMessageRouter.create(config)
            client.cefClient.addMessageRouter(myRouter)
        }
    }

    class Response(val result: String, val errCode: Int = 0, val errMsg: String? = null) {
        fun isSuccess() = errCode == 0
    }

    fun inject(script: String): String = """
        try {
            var _kb_res = String($script);
            window.${myFunc.myFuncName}({ request: _kb_res, onSuccess: function(r){}, onFailure: function(c,m){} });
        } catch (e) {
            window.${myFunc.myFuncName}({ request: 'KB_JS_ERROR: ' + (e.stack || e.message || e), onSuccess: function(r){}, onFailure: function(c,m){} });
        }
    """.trimIndent()

    fun addHandler(handler: (String) -> Response) {
        val cefHandler = object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(b: CefBrowser, f: CefFrame, queryId: Long, request: String, persistent: Boolean, callback: CefQueryCallback): Boolean {
                val response = handler(request)
                if (response.isSuccess()) callback.success(response.result) else callback.failure(response.errCode, response.errMsg)
                return true
            }
        }
        myFunc.myRouter.addHandler(cefHandler, false)
        myHandlerMap[handler] = cefHandler
    }

    override fun dispose() {
        if (myDisposeHelper.compareAndSet(false, true)) {
            val pool = myBrowser.myCefClient.jsQueryPool
            myHandlerMap.values.forEach { myFunc.myRouter.removeHandler(it) }
            myHandlerMap.clear()
            pool.releaseUsedSlot(myFunc)
        }
    }

    override fun isDisposed(): Boolean = myDisposeHelper.get()

    companion object {
        fun create(browser: KBCefBrowserBase): KBCefJSQuery {
            val pool = browser.myCefClient.jsQueryPool
            return KBCefJSQuery(browser, pool.useFreeSlot())
        }
    }
}
