package xyz.kbrowser.webview

// The screenshot preview window is JVM/Desktop only; no-op on Android.
actual fun showScreenshotPreview(bytes: ByteArray) = Unit
