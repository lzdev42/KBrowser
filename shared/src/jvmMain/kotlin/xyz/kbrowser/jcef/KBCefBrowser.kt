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
     * Outer Swing container, mirroring IDEA's JBCefBrowser.MyPanel.
     *
     * Key point: setBackground is overridden so any external background change is
     * propagated to the inner CEF heavyweight component (uiComp). Otherwise, in
     * non-OSR mode, uiComp keeps CEF's default background (white/gray) and clashes
     * visually with the outer JPanel.
     */
    private val myComponent: KBMyPanel = KBMyPanel()

    /** Finds the inner OSR component (only present in OSR mode). */
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

        // Set via myComponent so KBMyPanel's setBackground override propagates it to uiComp
        myComponent.background = Color.BLACK

        // Client property lets the shortcut provider find the browser (mirrors IDEA)
        myComponent.putClientProperty(KBCEFBROWSER_INSTANCE_PROP, this)

        // Register shortcuts (crucial for Mac)
        KCefShortcutProvider.registerShortcuts(myComponent, this)

        // Windows focus workaround from IDEA: ensure CEF gets focus on mouse press
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            uiComp.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (uiComp.isFocusable) {
                        myCefBrowser.setFocus(true)
                    }
                }
            })
        }

        // Focus passing: make sure focus reaches the inner OSR component
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
     * Sets the browser background color at both levels:
     * - the outer Swing container myComponent (KBMyPanel.setBackground forwards it to uiComp)
     * - the inner KBCefOsrComponent (the OSR render background)
     *
     * Must be called on the EDT since it mutates Swing component state.
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
     * Outer JPanel mirroring IDEA's JBCefBrowser.MyPanel.
     * - Overrides setBackground to propagate the color to the inner CEF heavyweight component
     * - Not focusable, so focus passes through to the inner KBCefOsrComponent
     * - Delegates InputMethodEvent/InputMethodRequests to the inner OSR component
     */
    private inner class KBMyPanel : JPanel(BorderLayout()) {
        /** Inner CEF heavyweight component, used by the setBackground passthrough. */
        var innerUiComp: Component? = null

        init {
            // The outer JPanel must not take focus: focus (and with it InputMethodEvents)
            // then goes directly to the inner KBCefOsrComponent
            isFocusable = false
            // IME safety net: still handle IME events if focus accidentally lands on this panel
            enableInputMethods(true)
        }

        // Mirrors IDEA: without this override, uiComp keeps the old color whenever
        // the myComponent background changes.
        override fun setBackground(bg: Color) {
            innerUiComp?.background = bg
            super.setBackground(bg)
        }

        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            return if (size.width > 0 && size.height > 0) size else Dimension(800, 600)
        }

        /**
         * Delegates IME requests to the inner [KBCefOsrComponent] so the OS input
         * method still gets correct caret bounds when AWT focus lands on this JPanel.
         */
        override fun getInputMethodRequests(): InputMethodRequests? {
            val osrComp = findOsrComponent()
            return osrComp?.inputMethodRequests ?: super.getInputMethodRequests()
        }

        /**
         * Forwards InputMethodEvents to the inner KBCefOsrComponent. AWT dispatches
         * input method events only to the focus owner and never propagates them to
         * child components, so manual forwarding is required when focus accidentally
         * lands on this panel.
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
