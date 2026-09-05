package xyz.kbrowser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import kotlin.system.exitProcess

/**
 * Comprehensive test of the three loading methods in non-OSR mode:
 * 1. loadHtml(htmlString) - HTML source string passed directly
 * 2. loadUrl("file:///path/to/file.html") - local file path
 * 3. loadUrl("https://www.example.com") - network URL
 *
 * Purpose: verify that after the loadHtml blank-screen fix, all three methods work
 * in non-OSR mode.
 */
fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== Non-OSR Comprehensive Load Test ======")

    var allPassed = true
    runBlocking {
        val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
        KBrowser.initializeConfig(storageDir, useOsr = false)
        initializeKBrowser()
        delay(5000)

        val page = KBrowser.newPage(viewportWidth = 1280, viewportHeight = 720)
        delay(3000)

        suspend fun pollForMarker(marker: String, timeoutMs: Long = 20000): String? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val r = withTimeoutOrNull(3000) {
                    page.evaluateJavascript("document.body?.innerText?.indexOf('$marker') >= 0 ? '$marker' : 'MISSING'")
                }
                if (r != null && r == marker) return r
                delay(500)
            }
            return null
        }

        println("\n[Test 1] === loadHtml with HTML code string ===")
        val htmlMarker = "LOADHTML_OK_${System.currentTimeMillis()}"
        val htmlContent = "<html><head><meta charset='utf-8'></head><body><div id='m'>$htmlMarker</div><h1>HTML String Test</h1></body></html>"
        println("[Test 1] calling loadHtml(htmlString)...")
        page.webView.loadHtml(htmlContent)
        val r1 = pollForMarker(htmlMarker)
        if (r1 != null) {
            println("[Test 1] ✅ PASS: loadHtml worked, marker found")
        } else {
            println("[Test 1] ❌ FAIL: loadHtml did not render marker $htmlMarker")
            allPassed = false
        }

        println("\n[Test 2] === loadUrl with file:// URL ===")
        val fileUrl = "file:" + System.getProperty("user.dir") + "/desktopApp/src/test/resources/viewport_test.html"
        println("[Test 2] loading file URL: $fileUrl")
        page.webView.loadUrl(fileUrl)
        val fileContent = try {
            java.io.File(System.getProperty("user.dir") + "/desktopApp/src/test/resources/viewport_test.html").readText()
        } catch (e: Exception) {
            println("[Test 2] ❌ Cannot read test HTML file: ${e.message}")
            ""
        }
        val fileMarker = extractMarkerFromFile(fileContent)
        println("[Test 2] expected file marker: $fileMarker")
        val r2 = pollForMarker(fileMarker)
        if (r2 != null) {
            println("[Test 2] ✅ PASS: loadUrl(file://) worked, marker found")
        } else {
            println("[Test 2] ❌ FAIL: loadUrl(file://) did not render marker $fileMarker")
            allPassed = false
            val url = withTimeoutOrNull(3000) { page.evaluateJavascript("window.location.href") }
            println("[Test 2] current URL: $url")
        }

        println("\n[Test 3] === loadUrl with https:// URL ===")
        val networkUrl = "https://example.com"
        println("[Test 3] loading network URL: $networkUrl")
        page.webView.loadUrl(networkUrl)
        val r3 = pollForMarker("Example Domain", timeoutMs = 30000)
        if (r3 != null) {
            println("[Test 3] ✅ PASS: loadUrl(https://) worked, page content found")
        } else {
            println("[Test 3] ❌ FAIL: loadUrl(https://) did not render expected content")
            allPassed = false
            val url = withTimeoutOrNull(3000) { page.evaluateJavascript("window.location.href") }
            println("[Test 3] current URL: $url")
        }

        page.close()
        KBrowser.shutdown()

        println("\n====== Test finished: ${if (allPassed) "ALL PASS ✅" else "SOME FAILED ❌"} ======")
    }
    if (!allPassed) exitProcess(1)
}

fun extractMarkerFromFile(content: String): String {
    val titleMatch = Regex("<title>(.*?)</title>").find(content)
    val title = titleMatch?.groupValues?.get(1)?.trim()
    if (!title.isNullOrEmpty()) return title
    val h1Match = Regex("<h1[^>]*>(.*?)</h1>").find(content)
    val h1 = h1Match?.groupValues?.get(1)?.trim()
    if (!h1.isNullOrEmpty()) return h1
    return "viewport"
}
