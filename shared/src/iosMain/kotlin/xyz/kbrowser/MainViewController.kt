package xyz.kbrowser

import androidx.compose.ui.window.ComposeUIViewController

import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

private var isInitialized = false

fun MainViewController() = ComposeUIViewController { 
    if (!isInitialized) {
        val paths = NSFileManager.defaultManager.URLsForDirectory(NSCachesDirectory, inDomains = NSUserDomainMask)
        val cacheDir = paths.first()?.toString()?.replace("file://", "") ?: "/tmp"
        xyz.kbrowser.webview.KBrowser.setConfigPath(cacheDir)
        xyz.kbrowser.webview.initializeKBrowser()
        isInitialized = true
    }
    App() 
}