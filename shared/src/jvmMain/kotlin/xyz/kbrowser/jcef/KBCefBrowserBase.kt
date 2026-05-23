package xyz.kbrowser.jcef

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRendering
import org.cef.handler.*
import org.cef.misc.Utils
import org.cef.network.CefRequest
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.KeyEvent
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent

abstract class KBCefBrowserBase protected constructor(builder: KBCefBrowserBuilder) : KBCefDisposable {
    companion object {
        const val KBCEFBROWSER_INSTANCE_PROP = "KBCefBrowser.instance"
        @JvmStatic
        protected var focusedBrowser: WeakReference<KBCefBrowserBase>? = null
    }

    val myCefClient: KBCefClient = builder.myClient ?: KBCefApp.getInstance().createClient()
    protected val myCefBrowser: CefBrowser
    private val myDefaultCefClient: Boolean = builder.myClient == null
    private val myIsOffScreenRendering: Boolean = builder.myIsOffScreenRendering
    private val myIsDisposed = AtomicBoolean(false)

    init {
        Disposer.register(myCefClient, this)

        var cefBrowser = builder.myCefBrowser
        if (cefBrowser == null) {
            val rendering = if (builder.myIsOffScreenRendering) CefRendering.OFFSCREEN else CefRendering.DEFAULT
            cefBrowser = myCefClient.cefClient.createBrowser(builder.myUrl, rendering, false, null)
        }
        myCefBrowser = cefBrowser

        myCefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onAfterCreated(browser: CefBrowser) {
            }

            override fun onBeforeClose(browser: CefBrowser) {
                // Handlers are removed in KBCefClient or during dispose
            }
        }, myCefBrowser)

        myCefClient.addKeyboardHandler(object : CefKeyboardHandlerAdapter() {
            override fun onKeyEvent(browser: CefBrowser, cefKeyEvent: CefKeyboardHandler.CefKeyEvent): Boolean {
                if (myIsOffScreenRendering) return false

                val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                val consume = focusOwner != browser.uiComponent
                
                if (consume && SystemInfo.isMac && KBCefEventUtils.isUpDownKeyEvent(cefKeyEvent)) return true

                val focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow ?: return true
                
                try {
                    val javaKeyEvent = KBCefEventUtils.convertCefKeyEvent(cefKeyEvent, focusedWindow)
                    Toolkit.getDefaultToolkit().systemEventQueue.postEvent(javaKeyEvent)
                } catch (e: Exception) {
                }

                return consume
            }
        }, myCefBrowser)
    }

    fun getCefBrowser(): CefBrowser = myCefBrowser

    abstract fun getComponent(): JComponent?

    override fun dispose() {
        if (myIsDisposed.compareAndSet(false, true)) {
            myCefBrowser.stopLoad()
            myCefBrowser.close(true)
            
            if (myDefaultCefClient) {
                Disposer.dispose(myCefClient)
            }
        }
    }

    override fun isDisposed(): Boolean = myIsDisposed.get()
}
