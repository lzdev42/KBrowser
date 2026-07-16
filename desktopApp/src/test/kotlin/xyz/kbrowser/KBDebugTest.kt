package xyz.kbrowser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.debug.DialogType
import xyz.kbrowser.webview.initializeKBrowser

/**
 * KBDebug API 测试 — 从 AI/MCP toolcall 视角验证。
 *
 * 验证点：
 * 1. enable() 后 inspect() 返回初始状态
 * 2. console.error 被捕获到 inspect().errors
 * 3. JS 异常被捕获到 inspect().errors
 * 4. 网络请求被捕获到 inspect().requests（XHR 过滤）
 * 5. 导航后 inspect().navigated == true
 * 6. alert/confirm 弹框被捕获到 inspect().activeDialog
 * 7. respondDialog() 能处理弹框
 * 8. snapshot() 返回正确的性能指标
 * 9. getResponseBody() 能取回响应体
 * 10. executeCdp() 原始 CDP 调用可用
 * 11. inspect() checkpoint：第二次调用只返回新事件
 * 12. disable() 后 inspect() 返回空
 */
fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== KBDebug API Test ======")

    runBlocking {
        val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
        KBrowser.initializeConfig(storageDir)
        initializeKBrowser()
        println("[Test] CefApp 初始化完成")
        delay(3000)

        val page = KBrowser.newHeadlessTab()
        val webView = page.webView
        val debug = webView.debug

        // 1. enable + initial inspect
        println("\n[Test] === 1. enable() + initial inspect() ===")
        debug.enable()
        delay(2000)
        val initial = debug.inspect()
        println("[Test] currentUrl: ${initial.currentUrl}")
        println("[Test] navigated: ${initial.navigated}")
        println("[Test] errors: ${initial.errors.size}")
        println("[Test] requests: ${initial.requests.size}")
        println("[Test] activeDialog: ${initial.activeDialog}")
        val pass1 = !initial.navigated && initial.activeDialog == null
        println("[Test] ${if (pass1) "PASS" else "FAIL"} initial inspect")

        // 2. console errors
        println("\n[Test] === 2. console errors ===")
        page.evaluateJavascript(
            """
            console.log('test log message');
            console.error('test error message');
            console.warn('test warn message');
            """.trimIndent()
        )
        delay(1000)

        val insp2 = debug.inspect()
        val consoleErrors = insp2.errors.filter { it.type == "console_error" }
        println("[Test] errors: ${insp2.errors.size}")
        consoleErrors.forEach { e ->
            println("[Test]   [console_error] ${e.message}")
        }
        val pass2 = consoleErrors.any { it.message.contains("test error") }
        println("[Test] ${if (pass2) "PASS" else "FAIL"} console errors captured")

        // 3. JS exception
        println("\n[Test] === 3. JS exception ===")
        page.evaluateJavascript(
            """
            setTimeout(function() {
                throw new Error('test exception for KBDebug');
            }, 0);
            """.trimIndent()
        )
        delay(1000)

        val insp3 = debug.inspect()
        val excErrors = insp3.errors.filter { it.type == "js_exception" }
        println("[Test] js_exception errors: ${excErrors.size}")
        excErrors.forEach { e ->
            println("[Test]   ${e.message}")
        }
        val pass3 = excErrors.any { it.message.contains("test exception") }
        println("[Test] ${if (pass3) "PASS" else "FAIL"} JS exception captured")

        // 4. network requests (XHR filtering)
        println("\n[Test] === 4. network requests ===")
        page.loadUrl("https://httpbin.org/get")
        delay(3000)

        val insp4 = debug.inspect()
        println("[Test] requests: ${insp4.requests.size}")
        insp4.requests.take(5).forEach { r ->
            println("[Test]   ${r.method} ${r.status ?: "?"} ${r.url.take(80)}")
        }
        val pass4 = insp4.requests.isNotEmpty()
        println("[Test] ${if (pass4) "PASS" else "FAIL"} network requests captured")

        // 5. navigation detected
        println("\n[Test] === 5. navigation detected ===")
        val pass5 = insp4.navigated && insp4.currentUrl.contains("httpbin.org/get")
        println("[Test] navigated: ${insp4.navigated}")
        println("[Test] currentUrl: ${insp4.currentUrl}")
        println("[Test] ${if (pass5) "PASS" else "FAIL"} navigation detected")

        // 6. dialog — alert (use setTimeout so evaluateJavascript doesn't block on alert())
        println("\n[Test] === 6. dialog (alert) ===")
        page.evaluateJavascript("setTimeout(function() { alert('test alert message'); }, 0)")
        delay(1000)

        val insp6 = debug.inspect()
        println("[Test] activeDialog: ${insp6.activeDialog}")
        val pass6 = insp6.activeDialog != null &&
            insp6.activeDialog!!.type == DialogType.ALERT &&
            insp6.activeDialog!!.message.contains("test alert")
        println("[Test] ${if (pass6) "PASS" else "FAIL"} alert dialog captured")

        // 7. respondDialog — dismiss the alert
        println("\n[Test] === 7. respondDialog (dismiss alert) ===")
        val dismissResult = debug.respondDialog(accept = false)
        delay(500)
        println("[Test] respondDialog returned: $dismissResult")
        val insp7 = debug.inspect()
        val pass7 = dismissResult && insp7.activeDialog == null
        println("[Test] activeDialog after dismiss: ${insp7.activeDialog}")
        println("[Test] ${if (pass7) "PASS" else "FAIL"} respondDialog dismisses alert")

        // 7b. dialog — confirm (use setTimeout to avoid blocking)
        println("\n[Test] === 7b. dialog (confirm) + respondDialog(accept=true) ===")
        page.evaluateJavascript("setTimeout(function() { confirm('are you sure?'); }, 0)")
        delay(1000)
        val insp7b = debug.inspect()
        println("[Test] activeDialog: ${insp7b.activeDialog}")
        val pass7bDialog = insp7b.activeDialog != null &&
            insp7b.activeDialog!!.type == DialogType.CONFIRM &&
            insp7b.activeDialog!!.message.contains("are you sure")
        val acceptResult = debug.respondDialog(accept = true)
        delay(500)
        val insp7bAfter = debug.inspect()
        val pass7b = pass7bDialog && acceptResult && insp7bAfter.activeDialog == null
        println("[Test] ${if (pass7b) "PASS" else "FAIL"} confirm dialog + accept")

        // 8. snapshot
        println("\n[Test] === 8. snapshot() ===")
        val snap = debug.snapshot()
        println("[Test] jsHeapUsedSize: ${snap.jsHeapUsedSize}")
        println("[Test] domNodeCount: ${snap.domNodeCount}")
        println("[Test] consoleErrorCount: ${snap.consoleErrorCount}")
        println("[Test] jsExceptionCount: ${snap.jsExceptionCount}")
        println("[Test] totalRequestCount: ${snap.totalRequestCount}")
        val pass8 = snap.jsHeapUsedSize > 0 && snap.domNodeCount > 0
        println("[Test] ${if (pass8) "PASS" else "FAIL"} snapshot")

        // 9. getResponseBody
        println("\n[Test] === 9. getResponseBody() ===")
        val firstReqId = insp4.requests.firstOrNull()?.requestId
        val pass9: Boolean
        if (firstReqId != null) {
            val body = debug.getResponseBody(firstReqId)
            println("[Test] response body length: ${body?.length ?: 0}")
            println("[Test] response body preview: ${body?.take(200)}")
            pass9 = body != null && body.isNotEmpty()
        } else {
            pass9 = false
            println("[Test] no XHR request to test getResponseBody")
        }
        println("[Test] ${if (pass9) "PASS" else "FAIL"} getResponseBody")

        // 10. executeCdp
        println("\n[Test] === 10. executeCdp() ===")
        val cdpResult = debug.executeCdp("Runtime.evaluate", """{"expression":"1+1"}""")
        println("[Test] CDP result: $cdpResult")
        val pass10 = cdpResult != null && cdpResult.contains("2")
        println("[Test] ${if (pass10) "PASS" else "FAIL"} executeCdp")

        // 11. inspect checkpoint — second call should only return new events
        println("\n[Test] === 11. inspect() checkpoint ===")
        val insp11a = debug.inspect()
        delay(500)
        page.evaluateJavascript("console.error('checkpoint test error')")
        delay(500)
        val insp11b = debug.inspect()
        val pass11 = insp11a.errors.isEmpty() && insp11b.errors.any { it.message.contains("checkpoint test") }
        println("[Test] first inspect errors: ${insp11a.errors.size}")
        println("[Test] second inspect errors: ${insp11b.errors.size}")
        println("[Test] ${if (pass11) "PASS" else "FAIL"} inspect checkpoint")

        // 12. disable
        println("\n[Test] === 12. disable() ===")
        debug.disable()
        val insp12 = debug.inspect()
        val pass12 = insp12.errors.isEmpty() && insp12.requests.isEmpty() && !insp12.navigated
        println("[Test] inspect after disable — errors: ${insp12.errors.size}, requests: ${insp12.requests.size}")
        println("[Test] ${if (pass12) "PASS" else "FAIL"} disable")

        // Summary
        println("\n====== Test Summary ======")
        val results = listOf(
            "initial inspect" to pass1,
            "console errors" to pass2,
            "JS exception" to pass3,
            "network requests" to pass4,
            "navigation detected" to pass5,
            "alert dialog" to pass6,
            "respondDialog dismiss" to pass7,
            "confirm dialog + accept" to pass7b,
            "snapshot" to pass8,
            "getResponseBody" to pass9,
            "executeCdp" to pass10,
            "inspect checkpoint" to pass11,
            "disable" to pass12
        )
        results.forEach { (name, pass) ->
            println("  ${if (pass) "PASS" else "FAIL"} $name")
        }
        val allPass = results.all { it.second }
        println("====== ${if (allPass) "ALL PASS" else "SOME FAILED"} ======")

        page.close()
        KBrowser.shutdown()
    }
}
