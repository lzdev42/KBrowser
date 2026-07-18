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

    @Volatile
    private var myCefFocusState = false

    /** IME 适配器，参照 IntelliJ 的 JBCefInputMethodAdapter */
    private val myInputMethodAdapter = KBCefInputMethodAdapter(this)

    // ---- Resize 节流（对应 IDEA 的 myResizeAlarm + myScheduleResizeMs）----
    // 使用 javax.swing.Timer（在 EDT 回调，线程安全），生命周期跟随 addNotify/removeNotify
    private var myResizeAlarm: Timer? = null
    private val myScheduleResizeMs = AtomicLong(-1L)
    private val myScaleInitialized = AtomicBoolean(false)

    /**
     * 首帧同步快速通道标志（EDT 单线程访问，无需 volatile）。
     *
     * 背景：Compose/AWT 首次 layout 触发的首次 reshape 必然落入 100ms 节流窗口
     * （now - myScheduleResizeMs ≈ 0 < RESIZE_DELAY_MS），导致 wasResized 被推迟。
     * 若此时 addNotify 里的 invokeLater 又已用 0/默认尺寸通知过 CEF，
     * CEF 会一直停留在初始大尺寸排版，直到下次真实拖拽窗口才会恢复。
     *
     * 此标志保证"首次有效 reshape 立即同步尺寸给 CEF"，绕过节流；
     * 仅首帧生效，后续拖拽仍走 100ms 节流，不影响防崩溃逻辑。
     */
    private var myFirstResizeSynced = false

    init {
        preferredSize = Dimension(800, 600)
        background = Color.BLACK

        enableEvents(
            AWTEvent.KEY_EVENT_MASK or
            AWTEvent.MOUSE_EVENT_MASK or
            AWTEvent.MOUSE_WHEEL_EVENT_MASK or
            AWTEvent.MOUSE_MOTION_EVENT_MASK or
            AWTEvent.INPUT_METHOD_EVENT_MASK or  // 启用输入法事件派发
            AWTEvent.FOCUS_EVENT_MASK           // 启用焦点事件派发，用于同步 CEF 焦点状态
        )

        // 启用输入法支持，使 OS IME 能向组件发送组合/提交事件
        enableInputMethods(true)

        // 注册输入法监听器，将 OS IME 事件转发给 CEF
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

    /** 供 [KBCefInputMethodAdapter] 访问 pixelDensity 用于 DPI 坐标转换 */
    val renderHandler: KBCefOsrHandler? get() = myRenderHandler

    fun setBrowser(browser: CefBrowser) {
        myBrowser = browser
        // 将浏览器实例传递给 IME 适配器，用于调用 ImeSetComposition/ImeCommitText
        myInputMethodAdapter.setBrowser(browser)
    }

    fun setRenderHandler(renderHandler: KBCefOsrHandler) {
        myRenderHandler = renderHandler

        // 将 Handler 的 IME 回调桥接到 InputMethodAdapter
        // 参照 IntelliJ: myRenderHandler.addCaretListener(myInputMethodAdapter)
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
        // 创建 resize alarm（EDT 线程安全的单次 Timer）
        // 注意：每次 reshape 都会 cancel 并重新 schedule，实现节流
        myResizeAlarm = Timer(RESIZE_DELAY_MS) {
            val browser = myBrowser ?: return@Timer
            val handler = myRenderHandler ?: return@Timer
            browser.wasResized(0, 0)
            handler.startResizePusher(browser, true)
        }.also { it.isRepeats = false }

        // 在 createImmediately() 之前主动读取当前屏幕的 pixelDensity，
        // 避免 JCEF 以默认 density=1.0 渲染导致首次截图尺寸不完整。
        // （正常情况下 graphicsConfiguration 事件会在组件显示后触发，但时序上晚于 createImmediately）
        try {
            val gc = graphicsConfiguration
            if (gc != null && !myScaleInitialized.get()) {
                val density = gc.defaultTransform.scaleX
                myRenderHandler?.setScreenInfo(density, 1.0)
                myScaleInitialized.set(true)
            }
        } catch (_: Exception) {}

        myBrowser?.createImmediately()

        SwingUtilities.invokeLater {
            val browser = myBrowser ?: return@invokeLater
            val handler = myRenderHandler ?: return@invokeLater
            browser.wasResized(0, 0)
            handler.startResizePusher(browser, true)
            ensureCefFocus()
        }
    }

    override fun removeNotify() {
        super.removeNotify()
        myResizeAlarm?.stop()
        myResizeAlarm = null
        myScheduleResizeMs.set(-1L)
        myFirstResizeSynced = false
        myScaleInitialized.set(false)
        myCefFocusState = false
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
     *
     * 首帧修复：首次有效 reshape（width/height > 0）立即同步，绕过 100ms 节流，
     * 避免 Compose 首次 layout 与 CEF 视口同步的时序偏差。
     */
    @Suppress("DEPRECATION")
    override fun reshape(x: Int, y: Int, w: Int, h: Int) {
        super.reshape(x, y, w, h)
        val alarm = myResizeAlarm ?: return   // addNotify 之前忽略
        val browser = myBrowser ?: return
        val handler = myRenderHandler ?: return

        // 首帧快速通道：第一次拿到有效尺寸时立即同步给 CEF，不进入节流窗口。
        // 否则首帧 reshape 必然落入 100ms 节流分支而被推迟，叠加 addNotify 的
        // invokeLater 用默认尺寸占位，会导致 CEF 维持初始大尺寸排版。
        if (!myFirstResizeSynced && w > 0 && h > 0) {
            myFirstResizeSynced = true
            browser.wasResized(0, 0)
            handler.startResizePusher(browser, true)
            // 同时记录起始时间，避免后续首个节流 alarm 立即判定为超时
            myScheduleResizeMs.set(System.currentTimeMillis())
            return
        }

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
     * 主动确保 CEF 焦点状态与 AWT 焦点一致。
     *
     * 这是 OSR 模式下中文输入能否工作的关键保障。
     * 仅依赖 [processFocusEvent] 被动通知 CEF 焦点状态是不够的，因为：
     * 1. Compose SwingPanel 的焦点代理机制可能导致 AWT FocusEvent 不到达 KBCefOsrComponent
     * 2. 外层容器（KBCefBrowser.myComponent）的焦点拦截可能阻止事件穿透
     * 3. 窗口切换、Compose 重组等场景下焦点事件可能丢失
     *
     * 如果不调用 [CefBrowser.setFocus]，CEF 内部认为浏览器没有焦点，
     * 会静默丢弃所有 IME 请求（ImeSetComposition/ImeCommitText），
     * 而 sendKeyEvent 不检查焦点状态所以英文字母能输入——这就是中文无法输入的根本原因。
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
     * 当 AWT 焦点变化时，同步通知 CEF。
     * OSR 模式下 CEF 没有原生窗口来检测焦点，必须由嵌入方显式通知。
     */
    override fun processFocusEvent(e: FocusEvent) {
        super.processFocusEvent(e)
        ensureCefFocus()
    }

    override fun processKeyEvent(e: KeyEvent) {
        super.processKeyEvent(e)
        // IME 组合期间，KEY_TYPED 事件由 InputMethodEvent 通道处理，
        // 不转发给 CEF，避免英文字母与中文输入双路冲突。
        if (e.id == KeyEvent.KEY_TYPED && myInputMethodAdapter.isComposing) {
            e.consume()
            return
        }
        myBrowser?.sendKeyEvent(e)
    }

    /**
     * 向 OS 输入法提供光标位置信息，使 IME 候选窗口正确定位。
     * 参照 IntelliJ 的 JBCefOsrComponent.getInputMethodRequests()。
     */
    override fun getInputMethodRequests(): java.awt.im.InputMethodRequests {
        return myInputMethodAdapter
    }

    /**
     * 从外层 [KBCefBrowser.myComponent] 转发 [InputMethodEvent]。
     * AWT 的 InputMethodEvent 只派发给焦点拥有者，不会自动穿透给子组件，
     * 因此当焦点落在外层 JPanel 上时，由外层手动调用此方法完成转发。
     *
     * 使用 [processInputMethodEvent] 而非直接调用 listener，保证
     * INPUT_METHOD_TEXT_CHANGED 和 CARET_POSITION_CHANGED 两种事件
     * 都能被正确路由到对应的 listener 方法。
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
