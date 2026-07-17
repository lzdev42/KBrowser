package xyz.kbrowser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import kotlin.system.exitProcess

/**
 * 非 OSR 模式下三种加载方式的综合测试：
 * 1. loadHtml(htmlString) - 直接传入 HTML 代码字符串
 * 2. loadUrl("file:///path/to/file.html") - 本地文件路径
 * 3. loadUrl("https://www.example.com") - 网络 URL
 *
 * 目的：确认修复 loadHtml 白屏问题后，三种加载方式都能在非 OSR 模式下正常工作。
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

        val page = KBrowser.newHeadlessTab()
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

        // === Test 1: loadHtml (HTML 代码字符串) ===
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

        // === Test 2: loadUrl with file:// URL ===
        println("\n[Test 2] === loadUrl with file:// URL ===")
        val fileUrl = "file:" + System.getProperty("user.dir") + "/desktopApp/src/test/resources/headless_viewport_test.html"
        println("[Test 2] loading file URL: $fileUrl")
        page.webView.loadUrl(fileUrl)
        // 读取文件里的 marker
        val fileContent = try {
            java.io.File(System.getProperty("user.dir") + "/desktopApp/src/test/resources/headless_viewport_test.html").readText()
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

        // === Test 3: loadUrl with https:// URL ===
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
