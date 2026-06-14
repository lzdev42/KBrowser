package xyz.kbrowser.webview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

actual suspend fun initializeKBrowser() {
    // 自动为宿主配置 Compose 混排所需的系统属性
    try {
        java.awt.Toolkit.getDefaultToolkit().setDynamicLayout(true)
    } catch (e: Exception) {
        println("[initializeKBrowser] WARNING: Failed to set AWT dynamic layout: ${e.message}")
    }
    System.setProperty("compose.interop.blending", "true")
    System.setProperty("compose.swing.render.on.graphics", "true")

    // Disable Chrome Runtime to use Alloy/OSR rendering.
    // Must be set before AWT/Compose initialization to prevent SIGSEGV.
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    System.setProperty("jcef.chrome.runtime", "false")
    System.setProperty("jcef.osr.enabled", "true")

    // Eagerly initialize JCEF before UI framework startup.
    withContext(Dispatchers.IO) {
        try {
            println("[initializeKBrowser] Getting KBCefApp instance...")
            System.out.flush()
            xyz.kbrowser.jcef.KBCefApp.getInstance(
                xyz.kbrowser.webview.KBrowser.storageDir
                    ?: throw IllegalStateException("KBrowser.initializeConfig() must be called before initializeKBrowser()")
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
                delay(50)
                attempts++
            }
            if (attempts >= 200) {
                println("[initializeKBrowser] WARNING: CefApp initialization timed out!")
                System.out.flush()
            }
        } catch (e: Exception) {
            println("[initializeKBrowser] ERROR initializing JCEF: ${e.message}")
            e.printStackTrace()
        }
    }
}
