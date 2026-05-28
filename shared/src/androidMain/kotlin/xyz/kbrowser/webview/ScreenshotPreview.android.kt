package xyz.kbrowser.webview

// 截图预览窗口仅在 JVM/Desktop 平台提供，Android 上为空操作。
actual fun showScreenshotPreview(bytes: ByteArray) = Unit
