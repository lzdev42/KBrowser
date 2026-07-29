package xyz.kbrowser.jcef

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputMethodEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.im.InputMethodRequests
import javax.swing.JComponent
import javax.swing.JPanel

class KBCefBrowser(builder: KBCefBrowserBuilder) : KBCefBrowserBase(builder) {

    constructor() : this(KBCefBrowserBuilder())
    constructor(url: String) : this(KBCefBrowserBuilder().setUrl(url))

    /**
     * 外层 Swing 容器，对齐 IDEA 的 JBCefBrowser.MyPanel。
     *
     * 关键点：重写 setBackground，让任何外部 setBackground 调用都同步到内层
     * CEF heavyweight 组件 (uiComp)。否则非 OSR 模式下，uiComp 会保留 CEF
     * 默认底色（白色/灰色），与外层 JPanel 形成视觉冲突。
     */
    private val myComponent: KBMyPanel = KBMyPanel()

    /** 查找内层 OSR 组件（仅 OSR 模式下存在） */
    private fun findOsrComponent(): KBCefOsrComponent? {
        for (comp in myComponent.components) {
            if (comp is KBCefOsrComponent) return comp
        }
        return null
    }

    init {
        val uiComp = myCefBrowser.uiComponent
        myComponent.innerUiComp = uiComp
        myComponent.add(uiComp, BorderLayout.CENTER)

        // 通过 myComponent.setBackground 间接同步到 uiComp，
        // 触发 KBMyPanel 中重写的 setBackground 透传逻辑（对齐 IDEA）。
        myComponent.background = Color.BLACK

        // IDEA Logic: Set property for shortcut provider to find the browser
        myComponent.putClientProperty(KBCEFBROWSER_INSTANCE_PROP, this)

        // Register shortcuts (crucial for Mac)
        KCefShortcutProvider.registerShortcuts(myComponent, this)

        // Windows focus fix from IDEA
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            uiComp.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (uiComp.isFocusable) {
                        myCefBrowser.setFocus(true)
                    }
                }
            })
        }

        // Focus passing — 确保焦点穿透到内层 OSR 组件
        myComponent.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                uiComp.requestFocusInWindow()
            }
        })
    }

    override fun getComponent(): JComponent = myComponent

    fun loadURL(url: String) {
        myCefBrowser.loadURL(url)
    }

    /**
     * 设置浏览器背景色，作用于两个层级：
     * - 外层 Swing 容器 myComponent（KBMyPanel 重写的 setBackground 会自动透传到 uiComp）
     * - 内层 KBCefOsrComponent（OSR 模式下的渲染底色）
     *
     * 必须在 EDT 上调用，因为涉及 Swing 组件属性修改。
     */
    fun setBrowserBackgroundColor(color: Color) {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            javax.swing.SwingUtilities.invokeLater { setBrowserBackgroundColor(color) }
            return
        }
        myComponent.background = color
        findOsrComponent()?.background = color
    }

    /**
     * 外层 JPanel 容器，对齐 IDEA JBCefBrowser.MyPanel。
     * - 重写 setBackground 让背景色透传到内层 CEF heavyweight 组件
     * - 不获取焦点，让焦点穿透到内层 KBCefOsrComponent
     * - 委托 InputMethodEvent/InputMethodRequests 给内层 OSR 组件
     */
    private inner class KBMyPanel : JPanel(BorderLayout()) {
        // 持有内层 CEF heavyweight 组件引用，用于 setBackground 透传
        var innerUiComp: Component? = null

        init {
            // 外层 JPanel 不应获取焦点，让焦点穿透到内层 KBCefOsrComponent
            // 这样 InputMethodEvent 会直接派发给 KBCefOsrComponent
            isFocusable = false
            // 启用 IME 作为安全网（当焦点意外落在此 JPanel 时仍能处理 IME 事件）
            enableInputMethods(true)
        }

        // 对齐 IDEA：重写 setBackground 让背景色透传到内层 CEF heavyweight 组件。
        // 否则每次外部修改 myComponent 背景色时，uiComp 仍保持旧色。
        override fun setBackground(bg: Color) {
            innerUiComp?.background = bg
            super.setBackground(bg)
        }

        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            return if (size.width > 0 && size.height > 0) size else Dimension(800, 600)
        }

        /**
         * 将 IME 请求委托给内部的 [KBCefOsrComponent]。
         * 当 AWT 焦点落在此 JPanel 上时，OS 输入法仍能获取正确的光标位置信息。
         */
        override fun getInputMethodRequests(): InputMethodRequests? {
            val osrComp = findOsrComponent()
            return osrComp?.inputMethodRequests ?: super.getInputMethodRequests()
        }

        /**
         * 将收到的 InputMethodEvent 转发给内层 KBCefOsrComponent。
         * AWT 的 InputMethodEvent 只派发给焦点拥有者，不会自动穿透给子组件，
         * 因此当焦点意外落在此 JPanel 上时，必须手动转发。
         */
        override fun processInputMethodEvent(e: InputMethodEvent) {
            val osrComp = findOsrComponent()
            if (osrComp != null) {
                osrComp.forwardInputMethodEvent(e)
            } else {
                super.processInputMethodEvent(e)
            }
        }
    }
}
