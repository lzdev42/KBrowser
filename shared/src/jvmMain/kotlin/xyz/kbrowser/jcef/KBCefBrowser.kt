package xyz.kbrowser.jcef

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
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

    private val myComponent: JPanel = object : JPanel(BorderLayout()) {
        init {
            // 外层 JPanel 不应获取焦点，让焦点穿透到内层 KBCefOsrComponent
            // 这样 InputMethodEvent 会直接派发给 KBCefOsrComponent
            isFocusable = false
            // 启用 IME 作为安全网（当焦点意外落在此 JPanel 时仍能处理 IME 事件）
            enableInputMethods(true)
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

    /**
     * 查找嵌套的 [KBCefOsrComponent]。
     * 用于将 IME 请求从外层 JPanel 委托到内层 OSR 组件。
     */
    private fun findOsrComponent(): KBCefOsrComponent? {
        for (comp in myComponent.components) {
            if (comp is KBCefOsrComponent) return comp
        }
        return null
    }

    init {
        val uiComp = myCefBrowser.uiComponent
        myComponent.add(uiComp, BorderLayout.CENTER)
        
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

        // resize 兜底：参照 IDEA JBCefBrowser.createComponent 注册 ComponentListener。
        // OSR 模式下 KBCefOsrComponent.reshape() 已由 AWT 布局自动触发（含 100ms 节流 + ResizePusher），
        // 无需在此重复处理。
        // 非 OSR（窗口）模式下，Compose SwingPanel 嵌入重量级 Canvas 时，
        // 拖拽时 Compose 布局变化可能不会及时同步给内层 Canvas 的 bounds，
        // 导致 CEF 原生窗口尺寸滞后。这里在外层 JPanel resize 时主动同步内层 uiComp 的 bounds。
        myComponent.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (findOsrComponent() == null) {
                    val w = myComponent.width
                    val h = myComponent.height
                    if (w > 0 && h > 0 && (uiComp.width != w || uiComp.height != h)) {
                        uiComp.setSize(w, h)
                    }
                }
            }
        })
    }

    override fun getComponent(): JComponent = myComponent

    fun loadURL(url: String) {
        myCefBrowser.loadURL(url)
    }
}
