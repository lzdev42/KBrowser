package xyz.kbrowser.jcef

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.im.InputMethodRequests
import javax.swing.JComponent
import javax.swing.JPanel

class KBCefBrowser(builder: KBCefBrowserBuilder) : KBCefBrowserBase(builder) {
    
    constructor() : this(KBCefBrowserBuilder())
    constructor(url: String) : this(KBCefBrowserBuilder().setUrl(url))

    private val myComponent: JPanel = object : JPanel(BorderLayout()) {
        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            return if (size.width > 0 && size.height > 0) size else Dimension(800, 600)
        }

        /**
         * 将 IME 请求委托给内部的 [KBCefOsrComponent]。
         * 当 AWT 焦点落在此 JPanel 上时，OS 输入法仍能获取正确的光标位置信息，
         * 避免因焦点未穿透到内层组件导致 IME 完全失效。
         */
        override fun getInputMethodRequests(): InputMethodRequests? {
            val osrComp = findOsrComponent()
            return osrComp?.inputMethodRequests ?: super.getInputMethodRequests()
        }

        /**
         * 不在外层 JPanel 注册 InputMethodListener，
         * 让 InputMethodEvent 直接穿透到内层 KBCefOsrComponent。
         * 如果在外层也注册，会导致同一事件被处理两次。
         */
        override fun addInputMethodListener(l: InputMethodListener?) {
            // 不调用 super，让事件穿透到内层
        }

        override fun removeInputMethodListener(l: InputMethodListener?) {
            // 对称空实现
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
    }

    override fun getComponent(): JComponent = myComponent

    fun loadURL(url: String) {
        myCefBrowser.loadURL(url)
    }
}
