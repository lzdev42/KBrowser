package xyz.kbrowser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser

/**
 * Regression test: call loadHtml / evaluateJavascript immediately after creating a
 * background page, to verify operations are queued rather than silently dropped while
 * the native peer is not ready yet.
 *
 * Before the fix: loadHtml was silently ignored and evaluateJavascript's callback never
 * fired, causing a timeout failure.
 * After the fix: loadHtml / evaluateJavascript queue until the native peer is ready,
 * then execute.
 */
fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== Timing Load Repro Test ======")
    var passed = false
    runBlocking {
        val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
        KBrowser.initializeConfig(storageDir)
        initializeKBrowser()
        delay(3000)

        val page = KBrowser.newPage(viewportWidth = 1280, viewportHeight = 720)
        println("[Test] page created, immediately calling loadHtml (no delay)")

        val marker = "TIMING_OK_${System.currentTimeMillis()}"
        val html = "<html><body><div id='marker'>$marker</div></body></html>"
        page.webView.loadHtml(html)

        val result = withTimeoutOrNull(20000) {
            page.evaluateJavascript("document.getElementById('marker')?.textContent || 'MISSING'")
        }

        if (result != null && result.contains(marker)) {
            println("[Test] ✅ PASS: loadHtml after creation succeeded, marker=$result")
            passed = true
        } else {
            println("[Test] ❌ FAIL: result=$result (expected to contain $marker)")
        }

        page.close()
        KBrowser.shutdown()
        println("====== Test finished ======")
    }
    if (!passed) {
        @Suppress("DEPRECATION")
        kotlin.system.exitProcess(1)
    }
}
