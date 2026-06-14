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
            if (builder.myIsOffScreenRendering) {
                val factory = KBCefOSRHandlerFactory.getInstance()
                val component = factory.createComponent(builder.myMouseWheelEventEnable)
                val handler = factory.createCefRenderHandler(component)

                // 构建 CefBrowserSettings（设置 windowless_frame_rate），对应 IDEA 的做法
                val settings: Any? = try {
                    val settingsClass = Class.forName("org.cef.CefBrowserSettings")
                    val s = settingsClass.getDeclaredConstructor().newInstance()
                    try {
                        settingsClass.getField("windowless_frame_rate").set(s, builder.myWindowlessFrameRate)
                    } catch (ignored: Exception) {}
                    s
                } catch (e: Exception) {
                    null
                }

                // 判断是否是 Remote 模式
                val isRemote = try {
                    val clazz = Class.forName("org.cef.CefApp")
                    val method = clazz.getMethod("isRemoteEnabled")
                    method.invoke(null) as Boolean
                } catch (e: Exception) {
                    false
                }

                var osrCreated = false
                if (isRemote) {
                    try {
                        val rendering = CefRendering.CefRenderingWithHandler(handler, component)
                        // 远程模式下，使用 createBrowser 并传递 isOffscreen = true
                        cefBrowser = myCefClient.cefClient.createBrowser(
                            builder.myUrl, rendering, true, builder.myRequestContext
                        )
                        osrCreated = true
                    } catch (e: Exception) {
                        println("[KBCefBrowserBase] 远程 OSR 浏览器创建失败: ${e.message}")
                    }
                } else {
                    // 本地模式，直接反射使用 CefBrowserOsrWithHandler
                    try {
                        val clazz = Class.forName("org.cef.browser.CefBrowserOsrWithHandler")
                        val settingsClass = if (settings != null) Class.forName("org.cef.CefBrowserSettings") else null
                        val constructor = if (settingsClass != null) {
                            clazz.getDeclaredConstructor(
                                org.cef.CefClient::class.java,
                                String::class.java,
                                org.cef.browser.CefRequestContext::class.java,
                                org.cef.handler.CefRenderHandler::class.java,
                                java.awt.Component::class.java,
                                org.cef.browser.CefBrowser::class.java,
                                java.awt.Point::class.java,
                                settingsClass
                            )
                        } else {
                            clazz.getDeclaredConstructor(
                                org.cef.CefClient::class.java,
                                String::class.java,
                                org.cef.browser.CefRequestContext::class.java,
                                org.cef.handler.CefRenderHandler::class.java,
                                java.awt.Component::class.java,
                                org.cef.browser.CefBrowser::class.java,
                                java.awt.Point::class.java,
                                Class.forName("org.cef.CefBrowserSettings")
                            )
                        }
                        constructor.isAccessible = true
                        cefBrowser = constructor.newInstance(
                            myCefClient.cefClient,
                            builder.myUrl,
                            builder.myRequestContext,
                            handler,
                            component,
                            null, // parentBrowser
                            null, // inspectAt
                            settings
                        ) as CefBrowser
                        osrCreated = true
                    } catch (e: Exception) {
                        println("[KBCefBrowserBase] 本地 OSR (CefBrowserOsrWithHandler) 创建失败: ${e.message}")
                    }
                }

                if (!osrCreated) {
                    // 降级：使用 CefRenderingWithHandler（不走 CefBrowserOsrWithHandler）
                    val rendering = CefRendering.CefRenderingWithHandler(handler, component)
                    cefBrowser = myCefClient.cefClient.createBrowser(
                        builder.myUrl, rendering, false, builder.myRequestContext
                    )
                }

                cefBrowser?.let { browser ->
                    if (browser.uiComponent is KBCefOsrComponent) {
                        (browser.uiComponent as KBCefOsrComponent).setBrowser(browser)
                    }
                }
            } else {
                println("====== [JCEF Native] Executing createBrowser with isOffscreen = FALSE (Native Windowed) ======")
                cefBrowser = myCefClient.cefClient.createBrowser(
                    builder.myUrl, CefRendering.DEFAULT, false, builder.myRequestContext
                )
            }
        } else {
            if (cefBrowser.uiComponent is KBCefOsrComponent) {
                (cefBrowser.uiComponent as KBCefOsrComponent).setBrowser(cefBrowser)
            }
        }
        myCefBrowser = checkNotNull(cefBrowser) { "cefBrowser must not be null after initialization" }

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

    fun createImmediately() {
        myCefBrowser.createImmediately()
    }

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
