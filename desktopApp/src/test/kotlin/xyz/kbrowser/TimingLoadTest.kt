package xyz.kbrowser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser

/**
 * 回归测试：创建 headless webView 后立即 loadHtml / evaluateJavascript，
 * 验证 native peer 未就绪时操作被排队而非静默丢弃。
 *
 * 修复前：loadHtml 被静默忽略，evaluateJavascript 的 callback 永不触发 → 超时失败。
 * 修复后：loadHtml / evaluateJavascript 排队等 native peer 就绪后执行 → 通过。
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

        val page = KBrowser.newHeadlessTab()
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
