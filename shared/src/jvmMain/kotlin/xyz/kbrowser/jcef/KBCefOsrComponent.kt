package xyz.kbrowser.jcef

import org.cef.browser.CefBrowser
import java.awt.*
import java.awt.event.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.roundToInt

/**
 * Off-screen rendering component.
 * Ported from IntelliJ IDEA's JBCefOsrComponent, keeping the resize debounce
 * logic (100ms) that prevents JCEF native crashes during fast window dragging.
 */
class KBCefOsrComponent : JPanel() {

    companion object {
        /** Matches IDEA: 100ms throttle; high-frequency wasResized calls crash JCEF's native layer. */
        private const val RESIZE_DELAY_MS = 100
    }

    @Volatile private var myRenderHandler: KBCefOsrHandler? = null
    @Volatile private var myBrowser: CefBrowser? = null

    private var myScale: Double = 1.0

    @Volatile
    private var myCefFocusState = false

    /** IME adapter, modeled after IntelliJ's JBCefInputMethodAdapter. */
    private val myInputMethodAdapter = KBCefInputMethodAdapter(this)

    // Uses javax.swing.Timer, which fires on the EDT (thread-safe).
    // Created in init rather than addNotify: background-page components are never
    // attached to any container, so addNotify never fires and the reshape throttle
    // must always be available.
    private var myResizeAlarm: Timer? = null
    private val myScheduleResizeMs = AtomicLong(-1L)
    private val myScaleInitialized = AtomicBoolean(false)

    init {
        preferredSize = Dimension(800, 600)
        background = Color.BLACK

        enableEvents(
            AWTEvent.KEY_EVENT_MASK or
            AWTEvent.MOUSE_EVENT_MASK or
            AWTEvent.MOUSE_WHEEL_EVENT_MASK or
            AWTEvent.MOUSE_MOTION_EVENT_MASK or
            AWTEvent.INPUT_METHOD_EVENT_MASK or
            AWTEvent.FOCUS_EVENT_MASK  // needed to sync CEF focus state
        )

        enableInputMethods(true)
        addInputMethodListener(myInputMethodAdapter)

        isFocusable = true
        focusTraversalKeysEnabled = false

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (isShowing) {
                    myRenderHandler?.setLocationOnScreen(locationOnScreen)
                }
                requestFocusInWindow()
                ensureCefFocus()
            }
        })

        // graphicsConfiguration changes (DPI switch, moving to another screen).
        // Matches IDEA: the first change is applied immediately, later ones are
        // delayed 1000ms to avoid interrupting internal browser state.
        addPropertyChangeListener("graphicsConfiguration") {
            if (myScaleInitialized.get()) {
                // JBR-7335 workaround
                SwingUtilities.invokeLater {
                    Timer(1000) { onGraphicsConfigurationChanged() }.also {
                        it.isRepeats = false
                        it.start()
                    }
                }
            } else {
                onGraphicsConfigurationChanged()
                myScaleInitialized.set(true)
            }
        }

        myResizeAlarm = createResizeAlarm()
    }

    private fun createResizeAlarm(): Timer = Timer(RESIZE_DELAY_MS) {
        val browser = myBrowser ?: return@Timer
        val handler = myRenderHandler ?: return@Timer
        browser.wasResized(0, 0)
        handler.startResizePusher(browser, true)
    }.also { it.isRepeats = false }

    /** Exposed so [KBCefInputMethodAdapter] can read pixelDensity for DPI coordinate conversion. */
    val renderHandler: KBCefOsrHandler? get() = myRenderHandler

    fun setBrowser(browser: CefBrowser) {
        myBrowser = browser
        myInputMethodAdapter.setBrowser(browser)
    }

    fun setRenderHandler(renderHandler: KBCefOsrHandler) {
        myRenderHandler = renderHandler

        renderHandler.addCaretListener(myInputMethodAdapter)

        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                if (isShowing) {
                    try {
                        myRenderHandler?.setLocationOnScreen(locationOnScreen)
                    } catch (ignore: IllegalComponentStateException) {}
                }
            }
        }

        try {
            if (isShowing) {
                myRenderHandler?.setLocationOnScreen(locationOnScreen)
            }
        } catch (ignore: IllegalComponentStateException) {}
    }

    override fun addNotify() {
        super.addNotify()
        myResizeAlarm ?: run { myResizeAlarm = createResizeAlarm() }

        // Read the current screen pixelDensity before createImmediately(): otherwise
        // JCEF renders at the default density 1.0 and the first frame is incomplete.
        // (The graphicsConfiguration event fires only after the component is shown,
        // later than createImmediately.)
        try {
            val gc = graphicsConfiguration
            if (gc != null && !myScaleInitialized.get()) {
                val density = gc.defaultTransform.scaleX
                myRenderHandler?.setScreenInfo(density, 1.0)
                myScaleInitialized.set(true)
            }
        } catch (_: Exception) {}

        // Matches IDEA: addNotify only calls createImmediately, no explicit wasResized —
        // reshape fires naturally on first layout, avoiding initial-size timing issues.
        myBrowser?.createImmediately()
    }

    override fun removeNotify() {
        super.removeNotify()
        // Stop the throttle timer but do not null it: background-page components may
        // never be mounted, and one re-mounted after removal must keep the reshape
        // throttle working.
        myResizeAlarm?.stop()
        myScheduleResizeMs.set(-1L)
        myScaleInitialized.set(false)
        myCefFocusState = false
        myRenderHandler?.stopResizePusher()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        myRenderHandler?.paint(g as Graphics2D)
    }

    /**
     * Resize debounce, mirroring IDEA's JBCefOsrComponent.reshape(): during fast
     * drags each reshape only resets the alarm; JCEF is notified via wasResized
     * only after RESIZE_DELAY_MS without a new reshape, since high-frequency
     * calls crash the native layer.
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun reshape(x: Int, y: Int, w: Int, h: Int) {
        super.reshape(x, y, w, h)
        val alarm = myResizeAlarm ?: return
        val browser = myBrowser ?: return
        val handler = myRenderHandler ?: return

        val now = System.currentTimeMillis()
        if (!alarm.isRunning) {
            myScheduleResizeMs.set(now)
        }
        alarm.stop()

        if (now - myScheduleResizeMs.get() >= RESIZE_DELAY_MS) {
            browser.wasResized(0, 0)
            handler.startResizePusher(browser, true)
        } else {
            alarm.start()
        }
    }

    override fun processMouseEvent(e: MouseEvent) {
        super.processMouseEvent(e)
        if (e.isConsumed) return

        val cefEvent = MouseEvent(
            e.component, e.id, e.`when`, e.modifiersEx,
            (e.x / myScale).roundToInt(),
            (e.y / myScale).roundToInt(),
            (e.xOnScreen / myScale).roundToInt(),
            (e.yOnScreen / myScale).roundToInt(),
            e.clickCount, e.isPopupTrigger, e.button
        )
        
        myBrowser?.sendMouseEvent(cefEvent)

        if (e.id == MouseEvent.MOUSE_PRESSED) {
            requestFocusInWindow()
            ensureCefFocus()
        }
    }

    override fun processMouseWheelEvent(e: MouseWheelEvent) {
        super.processMouseWheelEvent(e)
        if (e.isConsumed) return

        var valAmount = e.preciseWheelRotation * 10.0
        if (SystemInfo.isMac || SystemInfo.isLinux) {
            valAmount *= -1
        }

        myBrowser?.sendMouseWheelEvent(
            MouseWheelEvent(
                e.component, e.id, e.`when`, e.modifiersEx,
                (e.x / myScale).roundToInt(),
                (e.y / myScale).roundToInt(),
                (e.xOnScreen / myScale).roundToInt(),
                (e.yOnScreen / myScale).roundToInt(),
                e.clickCount, e.isPopupTrigger,
                e.scrollType,
                if (e.scrollType == MouseWheelEvent.WHEEL_UNIT_SCROLL) e.scrollAmount else 1,
                Math.round(valAmount).toInt(),
                valAmount
            )
        )
    }

    override fun processMouseMotionEvent(e: MouseEvent) {
        super.processMouseMotionEvent(e)

        myBrowser?.sendMouseEvent(
            MouseEvent(
                e.component, e.id, e.`when`, e.modifiersEx,
                (e.x / myScale).roundToInt(),
                (e.y / myScale).roundToInt(),
                (e.xOnScreen / myScale).roundToInt(),
                (e.yOnScreen / myScale).roundToInt(),
                e.clickCount, e.isPopupTrigger, e.button
            )
        )
    }

    /**
     * Forces the CEF focus state to match the AWT focus — the key to IME input
     * working in OSR mode. Relying on [processFocusEvent] alone is not enough:
     * Compose's SwingPanel focus proxy may swallow AWT FocusEvents, the outer
     * container ([KBCefBrowser.myComponent]) may intercept them, and focus events
     * can be lost on window switches or recomposition. Until [CefBrowser.setFocus]
     * is called, CEF considers itself unfocused and silently drops all IME requests
     * (ImeSetComposition/ImeCommitText), while sendKeyEvent ignores focus state —
     * which is why ASCII input works but CJK input does not.
     */
    private fun ensureCefFocus() {
        val browser = myBrowser ?: return
        val awtFocused = isFocusOwner
        if (awtFocused != myCefFocusState) {
            myCefFocusState = awtFocused
            browser.setFocus(awtFocused)
        }
    }

    /**
     * Syncs CEF focus on AWT focus changes. In OSR mode CEF has no native window
     * to detect focus, so the embedder must notify it explicitly.
     */
    override fun processFocusEvent(e: FocusEvent) {
        super.processFocusEvent(e)
        ensureCefFocus()
    }

    override fun processKeyEvent(e: KeyEvent) {
        super.processKeyEvent(e)
        // During IME composition, KEY_TYPED is handled via the InputMethodEvent
        // channel; do not forward it to CEF, or ASCII and composed CJK input would
        // double-feed.
        if (e.id == KeyEvent.KEY_TYPED && myInputMethodAdapter.isComposing) {
            e.consume()
            return
        }
        myBrowser?.sendKeyEvent(e)
    }

    /**
     * Provides the caret location to the OS input method so the IME candidate
     * window is positioned correctly. Modeled after IntelliJ's JBCefOsrComponent.
     */
    override fun getInputMethodRequests(): java.awt.im.InputMethodRequests {
        return myInputMethodAdapter
    }

    /**
     * Forwards [InputMethodEvent]s from the outer [KBCefBrowser.myComponent]. AWT
     * dispatches input method events only to the focus owner and never propagates
     * them to child components, so the outer panel calls this manually when it
     * holds focus. Routed through [processInputMethodEvent] so both
     * INPUT_METHOD_TEXT_CHANGED and CARET_POSITION_CHANGED reach their listeners.
     */
    fun forwardInputMethodEvent(e: InputMethodEvent) {
        processInputMethodEvent(e)
    }

    private fun onGraphicsConfigurationChanged() {
        try {
            val gc = graphicsConfiguration ?: return
            val transform = gc.defaultTransform
            val pixelDensity = transform.scaleX
            myScale = 1.0
            myRenderHandler?.setScreenInfo(pixelDensity, 1.0)
            myBrowser?.notifyScreenInfoChanged()
        } catch (ignore: Exception) {}
    }
}
