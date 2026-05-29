package xyz.kbrowser.webview

actual fun initializeKBrowser() {
    // Disable Chrome Runtime to use Alloy/OSR rendering.
    // Must be set before AWT/Compose initialization to prevent SIGSEGV.
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    System.setProperty("jcef.chrome.runtime", "false")
    System.setProperty("jcef.osr.enabled", "true")

    // Eagerly initialize JCEF before UI framework startup.
    try {
        println("[initializeKBrowser] Getting KBCefApp instance...")
        System.out.flush()
        xyz.kbrowser.jcef.KBCefApp.getInstance(
            xyz.kbrowser.webview.KBrowser.getConfigPath()
                ?: throw IllegalStateException("KBrowser.setConfigPath() must be called before initializeKBrowser()")
        )
        
        println("[initializeKBrowser] Waiting for CefApp initialization to complete...")
        System.out.flush()
        val cefAppClass = Class.forName("org.cef.CefApp")
        val getInstanceMethod = cefAppClass.getMethod("getInstance")
        val app = getInstanceMethod.invoke(null)
        val getStateMethod = cefAppClass.getMethod("getState")
        
        var attempts = 0
        while (attempts < 200) { // Max 10 seconds (200 * 50ms)
            val state = getStateMethod.invoke(app)
            if (state.toString() == "INITIALIZED") {
                println("[initializeKBrowser] CefApp successfully INITIALIZED!")
                System.out.flush()
                break
            }
            Thread.sleep(50)
            attempts++
        }
        if (attempts >= 200) {
            println("[initializeKBrowser] WARNING: CefApp initialization timed out!")
            System.out.flush()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
