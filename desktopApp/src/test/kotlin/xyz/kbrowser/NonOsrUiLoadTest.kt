package xyz.kbrowser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Regression test for the non-OSR white-screen bug: a UI page (no viewport) created in
 * non-OSR mode must render content loaded via loadHtml immediately, without a manual
 * reload. Before the fix, the first load was issued before the native browser existed
 * and was silently dropped by JCEF.
 */
fun main() {
    println("====== Non-OSR UI Page Load Test ======")

    var passed = false
    runBlocking {
        var frame: JFrame? = null
        try {
            val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
            KBrowser.initializeConfig(storageDir, useOsr = false)
            initializeKBrowser()

            val page = KBrowser.newPage() // UI page: no viewport args

            val marker = "NONOSR_UI_OK_${System.currentTimeMillis()}"
            val html = """
                <html><body style="background:#121214;color:#e0e0e0;">
                <h1 id="m">$marker</h1></body></html>
            """.trimIndent()

            // Host the component in a real window, then load immediately — the same
            // order the Compose demo uses (mount + LaunchedEffect load on entry).
            SwingUtilities.invokeLater {
                frame = JFrame("Non-OSR UI Page Load Test").apply {
                    defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    setSize(1280, 800)
                    contentPane.add((page.webView as xyz.kbrowser.webview.JvmWebView).browser.getComponent())
                    isVisible = true
                }
                page.webView.loadHtml(html)
            }

            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val r = withTimeoutOrNull(3000) {
                    page.evaluateJavascript("document.getElementById('m')?.textContent || 'MISSING'")
                }
                if (r != null && r.contains(marker)) {
                    passed = true
                    break
                }
                delay(500)
            }

            println("[Test] ${if (passed) "PASS" else "FAIL"}: first loadHtml rendered without manual reload")
            page.close()
            KBrowser.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            frame?.dispose()
        }
    }
    println("====== Test finished ======")
    if (!passed) exitProcess(1)
}
