package xyz.kbrowser.jcef

import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * Ported from IntelliJ IDEA's KCefShortcutProvider.
 * Fixes common shortcuts (Copy, Paste, etc.) on macOS and other platforms.
 * Included fallback for OSR mode where focusedFrame might be null.
 */
object KCefShortcutProvider {
    fun registerShortcuts(component: JComponent, browser: KBCefBrowserBase) {
        val inputMap = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionMap = component.actionMap

        val mask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx

        register(inputMap, actionMap, "Copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, mask)) {
            val frame = browser.getCefBrowser().focusedFrame ?: browser.getCefBrowser().mainFrame
            frame?.copy()
        }
        register(inputMap, actionMap, "Paste", KeyStroke.getKeyStroke(KeyEvent.VK_V, mask)) {
            val frame = browser.getCefBrowser().focusedFrame ?: browser.getCefBrowser().mainFrame
            frame?.paste()
        }
        register(inputMap, actionMap, "Cut", KeyStroke.getKeyStroke(KeyEvent.VK_X, mask)) {
            val frame = browser.getCefBrowser().focusedFrame ?: browser.getCefBrowser().mainFrame
            frame?.cut()
        }
        register(inputMap, actionMap, "SelectAll", KeyStroke.getKeyStroke(KeyEvent.VK_A, mask)) {
            val frame = browser.getCefBrowser().focusedFrame ?: browser.getCefBrowser().mainFrame
            frame?.selectAll()
        }
        register(inputMap, actionMap, "Undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask)) {
            val frame = browser.getCefBrowser().focusedFrame ?: browser.getCefBrowser().mainFrame
            frame?.undo()
        }
        register(inputMap, actionMap, "Redo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask or KeyEvent.SHIFT_DOWN_MASK)) {
            val frame = browser.getCefBrowser().focusedFrame ?: browser.getCefBrowser().mainFrame
            frame?.redo()
        }
    }

    private fun register(inputMap: javax.swing.InputMap, actionMap: javax.swing.ActionMap, name: String, stroke: KeyStroke, action: () -> Unit) {
        inputMap.put(stroke, name)
        actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                action()
            }
        })
    }
}
