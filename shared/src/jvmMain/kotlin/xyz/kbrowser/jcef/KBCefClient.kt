package xyz.kbrowser.jcef

import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.*
import org.cef.handler.*
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.SwingUtilities

class KBCefClient(val cefClient: CefClient) : KBCefDisposable {
    private val myIsDisposed = AtomicBoolean(false)
    val jsQueryPool = JSQueryPool()
    
    private val myContextMenuHandler = HandlerSupport<CefContextMenuHandler>()
    private val myDialogHandler = HandlerSupport<CefDialogHandler>()
    private val myDisplayHandler = HandlerSupport<CefDisplayHandler>()
    private val myDownloadHandler = HandlerSupport<CefDownloadHandler>()
    private val myDragHandler = HandlerSupport<CefDragHandler>()
    private val myPermissionHandler = HandlerSupport<CefPermissionHandler>()
    private val myFocusHandler = HandlerSupport<CefFocusHandler>()
    private val myJSDialogHandler = HandlerSupport<CefJSDialogHandler>()
    private val myKeyboardHandler = HandlerSupport<CefKeyboardHandler>()
    private val myLifeSpanHandler = HandlerSupport<CefLifeSpanHandler>()
    private val myLoadHandler = HandlerSupport<CefLoadHandler>()
    private val myRequestHandler = HandlerSupport<CefRequestHandler>()

    init {
        Disposer.register(KBCefApp.getInstance(), this)
    }

    override fun isDisposed(): Boolean = myIsDisposed.get()

    override fun dispose() {
        if (myIsDisposed.compareAndSet(false, true)) {
            cefClient.dispose()
        }
    }

    fun createBrowser(url: String): KBCefBrowser {
        val builder = KBCefBrowserBuilder()
            .setUrl(url)
            .setClient(this)
        return KBCefBrowser(builder)
    }

    inner class JSQueryPool {
        private val freeSlots = ConcurrentLinkedQueue<KBCefJSQuery.JSQueryFunc>()
        private val myCounter = AtomicInteger(0)
        
        init {
            // Pre-populate 5 slots so their routers are registered early before any browser is created.
            try {
                val createAction = {
                    for (i in 0 until 5) {
                        freeSlots.add(KBCefJSQuery.JSQueryFunc(this@KBCefClient, myCounter.getAndIncrement()))
                    }
                }
                if (SwingUtilities.isEventDispatchThread()) {
                    createAction()
                } else {
                    SwingUtilities.invokeAndWait(createAction)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        fun useFreeSlot(): KBCefJSQuery.JSQueryFunc {
            var slot = freeSlots.poll()
            if (slot != null) return slot
            
            synchronized(this) {
                slot = freeSlots.poll()
                if (slot == null) {
                    var newSlot: KBCefJSQuery.JSQueryFunc? = null
                    val createAction = {
                        newSlot = KBCefJSQuery.JSQueryFunc(this@KBCefClient, myCounter.getAndIncrement())
                    }
                    if (SwingUtilities.isEventDispatchThread()) {
                        createAction()
                    } else {
                        SwingUtilities.invokeAndWait(createAction)
                    }
                    slot = newSlot!!
                }
            }
            return slot!!
        }
        
        fun releaseUsedSlot(slot: KBCefJSQuery.JSQueryFunc) {
            freeSlots.add(slot)
        }
    }

    private inner class HandlerSupport<T> {
        private val myMap = Collections.synchronizedMap(LinkedHashMap<CefBrowser, MutableList<T>>())

        fun add(handler: T, browser: CefBrowser, onInit: () -> Unit): KBCefClient {
            val list = myMap.getOrPut(browser) {
                if (myMap.isEmpty()) onInit()
                CopyOnWriteArrayList<T>()
            }
            list.add(handler)
            return this@KBCefClient
        }

        fun remove(handler: T, browser: CefBrowser, onClear: () -> Unit) {
            val list = myMap[browser]
            if (list != null) {
                list.remove(handler)
                if (list.isEmpty()) {
                    myMap.remove(browser)
                    if (myMap.isEmpty()) onClear()
                }
            }
        }

        fun get(browser: CefBrowser): List<T>? = myMap[browser]

        fun handleAll(browser: CefBrowser, action: (T) -> Unit) {
            get(browser)?.forEach(action)
        }

        fun <R> handleBooleanAny(browser: CefBrowser, initial: R, action: (T) -> Boolean): Boolean {
            var result = false
            get(browser)?.forEach { result = result or action(it) }
            return result
        }
    }

    // --- Context Menu ---
    fun addContextMenuHandler(handler: CefContextMenuHandler, browser: CefBrowser) {
        myContextMenuHandler.add(handler, browser) {
            cefClient.addContextMenuHandler(object : CefContextMenuHandlerAdapter() {
                override fun onBeforeContextMenu(b: CefBrowser, f: CefFrame, p: CefContextMenuParams, m: CefMenuModel) {
                    myContextMenuHandler.handleAll(b) { it.onBeforeContextMenu(b, f, p, m) }
                }
                override fun onContextMenuCommand(b: CefBrowser, f: CefFrame, p: CefContextMenuParams, c: Int, e: Int): Boolean {
                    return myContextMenuHandler.handleBooleanAny(b, false) { it.onContextMenuCommand(b, f, p, c, e) }
                }
            })
        }
    }

    // --- Display ---
    fun addDisplayHandler(handler: CefDisplayHandler, browser: CefBrowser) {
        myDisplayHandler.add(handler, browser) {
            cefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                override fun onAddressChange(b: CefBrowser, f: CefFrame, u: String) {
                    myDisplayHandler.handleAll(b) { it.onAddressChange(b, f, u) }
                }
                override fun onTitleChange(b: CefBrowser, t: String) {
                    myDisplayHandler.handleAll(b) { it.onTitleChange(b, t) }
                }
            })
        }
    }

    // --- LifeSpan ---
    fun addLifeSpanHandler(handler: CefLifeSpanHandler, browser: CefBrowser) {
        myLifeSpanHandler.add(handler, browser) {
            cefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                override fun onAfterCreated(b: CefBrowser) {
                    myLifeSpanHandler.handleAll(b) { it.onAfterCreated(b) }
                }
                override fun onBeforeClose(b: CefBrowser) {
                    myLifeSpanHandler.handleAll(b) { it.onBeforeClose(b) }
                }
                override fun onBeforePopup(
                    b: CefBrowser,
                    frame: CefFrame,
                    targetUrl: String,
                    targetFrameName: String
                ): Boolean {
                    var cancel = false
                    myLifeSpanHandler.get(b)?.forEach {
                        if (it.onBeforePopup(b, frame, targetUrl, targetFrameName)) cancel = true
                    }
                    return cancel
                }
            })
        }
    }
    fun removeLifeSpanHandler(handler: CefLifeSpanHandler, browser: CefBrowser) = myLifeSpanHandler.remove(handler, browser) { cefClient.removeLifeSpanHandler() }

    // --- Load ---
    fun addLoadHandler(handler: CefLoadHandler, browser: CefBrowser) {
        myLoadHandler.add(handler, browser) {
            cefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadingStateChange(b: CefBrowser, isLoading: Boolean, goBack: Boolean, goForward: Boolean) {
                    myLoadHandler.handleAll(b) { it.onLoadingStateChange(b, isLoading, goBack, goForward) }
                }
                override fun onLoadStart(b: CefBrowser, f: CefFrame, t: CefRequest.TransitionType) {
                    myLoadHandler.handleAll(b) { it.onLoadStart(b, f, t) }
                }
                override fun onLoadEnd(b: CefBrowser, f: CefFrame, s: Int) {
                    myLoadHandler.handleAll(b) { it.onLoadEnd(b, f, s) }
                }
                override fun onLoadError(b: CefBrowser, f: CefFrame, e: CefLoadHandler.ErrorCode, t: String, u: String) {
                    myLoadHandler.handleAll(b) { it.onLoadError(b, f, e, t, u) }
                }
            })
        }
    }
    fun removeLoadHandler(handler: CefLoadHandler, browser: CefBrowser) = myLoadHandler.remove(handler, browser) { cefClient.removeLoadHandler() }

    // --- Keyboard ---
    fun addKeyboardHandler(handler: CefKeyboardHandler, browser: CefBrowser) {
        myKeyboardHandler.add(handler, browser) {
            cefClient.addKeyboardHandler(object : CefKeyboardHandlerAdapter() {
                override fun onKeyEvent(b: CefBrowser, e: CefKeyboardHandler.CefKeyEvent): Boolean {
                    return myKeyboardHandler.handleBooleanAny(b, false) { it.onKeyEvent(b, e) }
                }
            })
        }
    }
    fun removeKeyboardHandler(handler: CefKeyboardHandler, browser: CefBrowser) = myKeyboardHandler.remove(handler, browser) { cefClient.removeKeyboardHandler() }

    // --- Request ---
    fun addRequestHandler(handler: CefRequestHandler, browser: CefBrowser) {
        myRequestHandler.add(handler, browser) {
            cefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(b: CefBrowser, f: CefFrame, r: CefRequest, g: Boolean, i: Boolean): Boolean {
                    return myRequestHandler.handleBooleanAny(b, false) { it.onBeforeBrowse(b, f, r, g, i) }
                }
            })
        }
    }
    fun removeRequestHandler(handler: CefRequestHandler, browser: CefBrowser) = myRequestHandler.remove(handler, browser) { cefClient.removeRequestHandler() }

    // --- Dialog ---
    fun addDialogHandler(handler: CefDialogHandler, browser: CefBrowser) {
        myDialogHandler.add(handler, browser) {
            cefClient.addDialogHandler(object : CefDialogHandler {
                override fun onFileDialog(
                    b: CefBrowser,
                    mode: CefDialogHandler.FileDialogMode,
                    title: String,
                    defaultFilePath: String,
                    acceptFilters: Vector<String>,
                    acceptExtensions: Vector<String>,
                    acceptDescriptions: Vector<String>,
                    callback: CefFileDialogCallback
                ): Boolean {
                    return myDialogHandler.handleBooleanAny(b, false) {
                        it.onFileDialog(b, mode, title, defaultFilePath, acceptFilters, acceptExtensions, acceptDescriptions, callback)
                    }
                }
            })
        }
    }
    fun removeDialogHandler(handler: CefDialogHandler, browser: CefBrowser) = myDialogHandler.remove(handler, browser) { cefClient.removeDialogHandler() }

    // --- JS Dialog (alert / confirm / prompt) ---
    fun addJSDialogHandler(handler: CefJSDialogHandler, browser: CefBrowser) {
        myJSDialogHandler.add(handler, browser) {
            cefClient.addJSDialogHandler(object : CefJSDialogHandlerAdapter() {
                override fun onJSDialog(
                    b: CefBrowser,
                    originUrl: String,
                    dialogType: CefJSDialogHandler.JSDialogType,
                    messageText: String,
                    defaultPromptText: String?,
                    callback: CefJSDialogCallback,
                    suppressMessage: BoolRef
                ): Boolean {
                    return myJSDialogHandler.handleBooleanAny(b, false) {
                        it.onJSDialog(b, originUrl, dialogType, messageText, defaultPromptText, callback, suppressMessage)
                    }
                }
            })
        }
    }
    fun removeJSDialogHandler(handler: CefJSDialogHandler, browser: CefBrowser) = myJSDialogHandler.remove(handler, browser) { cefClient.removeJSDialogHandler() }

    // --- Permission (Media Access: camera / microphone) ---
    fun addPermissionHandler(handler: CefPermissionHandler, browser: CefBrowser) {
        myPermissionHandler.add(handler, browser) {
            cefClient.addPermissionHandler(object : CefPermissionHandler {
                override fun onRequestMediaAccessPermission(
                    b: CefBrowser,
                    frame: CefFrame,
                    requestingOrigin: String,
                    requestedPermissions: Int,
                    callback: CefMediaAccessCallback
                ): Boolean {
                    return myPermissionHandler.handleBooleanAny(b, false) {
                        it.onRequestMediaAccessPermission(b, frame, requestingOrigin, requestedPermissions, callback)
                    }
                }
            })
        }
    }
    fun removePermissionHandler(handler: CefPermissionHandler, browser: CefBrowser) = myPermissionHandler.remove(handler, browser) { cefClient.removePermissionHandler() }
}
