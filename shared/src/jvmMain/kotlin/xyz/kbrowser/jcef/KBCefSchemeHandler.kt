package xyz.kbrowser.jcef

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.util.Collections
import java.util.HashMap
import java.util.WeakHashMap

class KBCefFileSchemeHandlerFactory : CefSchemeHandlerFactory {
    companion object {
        const val FILE_SCHEME_NAME = "file"
        val LOADHTML_REQUEST_MAP = WeakHashMap<CefBrowser, MutableMap<String, String>>()

        fun getInitMap(browser: CefBrowser): MutableMap<String, String> {
            var map = LOADHTML_REQUEST_MAP[browser]
            if (map == null) {
                synchronized(LOADHTML_REQUEST_MAP) {
                    map = LOADHTML_REQUEST_MAP[browser]
                    if (map == null) {
                        map = Collections.synchronizedMap(HashMap<String, String>())
                        LOADHTML_REQUEST_MAP[browser] = map
                    }
                }
            }
            return map!!
        }
    }

    override fun create(browser: CefBrowser, frame: CefFrame, schemeName: String, request: CefRequest): CefResourceHandler? {
        if (schemeName != FILE_SCHEME_NAME) return null
        val url = request.url ?: return null
        val map = LOADHTML_REQUEST_MAP[browser]
        if (map != null) {
            val html = map[url]
            if (html != null) return KBCefLoadHtmlResourceHandler(html)
        }
        return null 
    }
}

class KBCefLoadHtmlResourceHandler(html: String) : CefResourceHandlerAdapter() {
    private var stream: java.io.InputStream? = null
    private val bytes = html.toByteArray(Charsets.UTF_8)

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        stream = bytes.inputStream()
        callback.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: org.cef.misc.IntRef, redirectUrl: org.cef.misc.StringRef) {
        response.mimeType = "text/html"
        response.status = 200
        responseLength.set(bytes.size)
    }

    override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: org.cef.misc.IntRef, callback: CefCallback): Boolean {
        val available = stream?.available() ?: 0
        if (available > 0) {
            val toRead = minOf(bytesToRead, available)
            stream?.read(dataOut, 0, toRead)
            bytesRead.set(toRead)
            return true
        }
        bytesRead.set(0)
        return false
    }
}
