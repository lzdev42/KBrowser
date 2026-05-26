package xyz.kbrowser

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import xyz.kbrowser.webview.initializeKBrowser

fun main() {

    java.awt.Toolkit.getDefaultToolkit().setDynamicLayout(true)
    System.setProperty("compose.interop.blending", "true")
    System.setProperty("compose.swing.render.on.graphics", "true")
    System.setProperty("jcef.remote.enabled", "true")
    initializeKBrowser()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "KBrowser",
        ) {
            App()
        }
    }
}