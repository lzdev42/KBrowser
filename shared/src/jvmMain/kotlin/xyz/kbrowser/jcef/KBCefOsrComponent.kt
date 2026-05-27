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
        /** 与 IDEA 保持一致：100ms 节流，防止高频 wasResized 导致 JCEF native 崩溃 */
        private const val RESIZE_DELAY_MS = 100
    }

    @Volatile private var myRenderHandler: KBCefOsrHandler? = null
    @Volatile private var myBrowser: CefBrowser? = null

    private var myScale: Double = 1.0

    // ---- Resize 节流（对应 IDEA 的 myResizeAlarm + myScheduleResizeMs）----
    // 使用 javax.swing.Timer（在 EDT 回调，线程安全），生命周期跟随 addNotify/removeNotify
    private var myResizeAlarm: Timer? = null
    private val myScheduleResizeMs = AtomicLong(-1L)
    private val myScaleInitialized = AtomicBoolean(false)

    init {
        preferredSize = Dimension(800, 600)
        background = Color.WHITE

        enableEvents(
            AWTEvent.KEY_EVENT_MASK or
            AWTEvent.MOUSE_EVENT_MASK or
            AWTEvent.MOUSE_WHEEL_EVENT_MASK or
            AWTEvent.MOUSE_MOTION_EVENT_MASK
        )

        isFocusable = true
        focusTraversalKeysEnabled = false

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (isShowing) {
                    myRenderHandler?.setLocationOnScreen(locationOnScreen)
                }
                requestFocusInWindow()
            }
        })

        // graphicsConfiguration 变更（分辨率切换、移动到另一块屏幕）
        // 对应 IDEA：第一次不延迟，后续延迟 1000ms，避免 browser 内部状态被打断
        addPropertyChangeListener("graphicsConfiguration") {
            if (myScaleInitialized.get()) {
                // 已初始化过，延迟 1000ms 再更新，对应 IDEA 的 JBR-7335 workaround
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
    }

    fun setBrowser(browser: CefBrowser) {
        myBrowser = browser
    }

    fun setRenderHandler(renderHandler: KBCefOsrHandler) {
        myRenderHandler = renderHandler

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
        // 创建 resize alarm（EDT 线程安全的单次 Timer）
        // 注意：每次 reshape 都会 cancel 并重新 schedule，实现节流
        myResizeAlarm = Timer(RESIZE_DELAY_MS) {
            val browser = myBrowser ?: return@Timer
            val handler = myRenderHandler ?: return@Timer
            browser.wasResized(0, 0)
            handler.startResizePusher(browser, true)
        }.also { it.isRepeats = false }

        myBrowser?.createImmediately()
    }

    override fun removeNotify() {
        super.removeNotify()
        myResizeAlarm?.stop()
        myResizeAlarm = null
        myScheduleResizeMs.set(-1L)
        myScaleInitialized.set(false)
        myRenderHandler?.stopResizePusher()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        myRenderHandler?.paint(g as Graphics2D)
    }

    /**
     * resize 节流核心逻辑，1:1 对应 IDEA JBCefOsrComponent.reshape()：
     * - 快速拖拽时每次 reshape 只重置 alarm，不立即调用 wasResized
     * - 超过 RESIZE_DELAY_MS 没有新的 reshape 时才真正通知 JCEF
     * - 防止高频调用导致 native 层崩溃
     */
    @Suppress("DEPRECATION")
    override fun reshape(x: Int, y: Int, w: Int, h: Int) {
        super.reshape(x, y, w, h)
        val alarm = myResizeAlarm ?: return   // addNotify 之前忽略
        val browser = myBrowser ?: return
        val handler = myRenderHandler ?: return

        val now = System.currentTimeMillis()
        if (!alarm.isRunning) {
            // 第一次 reshape，记录开始时间
            myScheduleResizeMs.set(now)
        }
        alarm.stop()

        if (now - myScheduleResizeMs.get() >= RESIZE_DELAY_MS) {
            // 距上次 reshape 超过 100ms（拖拽停止），立即通知
            browser.wasResized(0, 0)
            handler.startResizePusher(browser, true)
        } else {
            // 还在快速拖拽中，延迟执行
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
        
        println("[DEBUG-KBCefOsr] processMouseEvent 收到事件: id=${e.id}, awtX=${e.x}, awtY=${e.y}, myScale=$myScale -> 转换后发送给 CEF: x=${cefEvent.x}, y=${cefEvent.y}")

        myBrowser?.sendMouseEvent(cefEvent)

        if (e.id == MouseEvent.MOUSE_PRESSED) {
            requestFocusInWindow()
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

    override fun processKeyEvent(e: KeyEvent) {
        super.processKeyEvent(e)
        myBrowser?.sendKeyEvent(e)
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
