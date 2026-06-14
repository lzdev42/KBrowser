package xyz.kbrowser

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import xyz.kbrowser.webview.initializeKBrowser

fun main() {
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "KBrowser",
        ) {
            App()
        }
    }
}