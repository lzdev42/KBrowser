package xyz.kbrowser

import androidx.compose.ui.window.ComposeUIViewController

import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

fun MainViewController() = ComposeUIViewController {
    var isInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!isInitialized) {
            val paths = NSFileManager.defaultManager.URLsForDirectory(NSCachesDirectory, inDomains = NSUserDomainMask)
            val cacheDir = paths.first()?.toString()?.replace("file://", "") ?: "/tmp"
            xyz.kbrowser.webview.KBrowser.initializeConfig(cacheDir)
            xyz.kbrowser.webview.initializeKBrowser()
            isInitialized = true
        }
    }

    if (isInitialized) {
        App()
    }
}
