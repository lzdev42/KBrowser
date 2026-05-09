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
 */
object KCefShortcutProvider {
    fun registerShortcuts(component: JComponent, browser: KBCefBrowser) {
        val inputMap = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionMap = component.actionMap

        val mask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx

        register(inputMap, actionMap, "Copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, mask)) {
            browser.getCefBrowser().focusedFrame?.copy()
        }
        register(inputMap, actionMap, "Paste", KeyStroke.getKeyStroke(KeyEvent.VK_V, mask)) {
            browser.getCefBrowser().focusedFrame?.paste()
        }
        register(inputMap, actionMap, "Cut", KeyStroke.getKeyStroke(KeyEvent.VK_X, mask)) {
            browser.getCefBrowser().focusedFrame?.cut()
        }
        register(inputMap, actionMap, "SelectAll", KeyStroke.getKeyStroke(KeyEvent.VK_A, mask)) {
            browser.getCefBrowser().focusedFrame?.selectAll()
        }
        register(inputMap, actionMap, "Undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask)) {
            browser.getCefBrowser().focusedFrame?.undo()
        }
        register(inputMap, actionMap, "Redo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask or KeyEvent.SHIFT_DOWN_MASK)) {
            browser.getCefBrowser().focusedFrame?.redo()
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
