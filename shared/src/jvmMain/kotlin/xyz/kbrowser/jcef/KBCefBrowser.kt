package xyz.kbrowser.jcef

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

        // Focus passing
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
