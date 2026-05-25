package xyz.kbrowser.webview

actual fun initializeKBrowser() {
    // Disable Chrome Runtime to use Alloy/OSR rendering.
    // Must be set before AWT/Compose initialization to prevent SIGSEGV.
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    System.setProperty("jcef.chrome.runtime", "false")
    System.setProperty("jcef.osr.enabled", "false")

    // Eagerly initialize JCEF before UI framework startup.
    try {
        xyz.kbrowser.jcef.KBCefApp.getInstance()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
