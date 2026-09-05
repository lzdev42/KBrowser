package xyz.kbrowser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.debug.DialogType
import xyz.kbrowser.webview.initializeKBrowser
import kotlin.system.exitProcess

/**
 * KBDebug API test — verifies the debug API from an AI/MCP tool-call perspective.
 *
 * Checks:
 * 1. inspect() returns the initial state after enable()
 * 2. console.error is captured in inspect().errors
 * 3. JS exceptions are captured in inspect().errors
 * 4. Network requests are captured in inspect().requests (XHR filtering)
 * 5. inspect().navigated == true after navigation
 * 6. alert/confirm dialogs are captured in inspect().activeDialog
 * 7. respondDialog() can dismiss/accept dialogs
 * 8. snapshot() returns correct performance metrics
 * 9. getResponseBody() retrieves the response body
 * 10. executeCdp() works for raw CDP calls
 * 11. inspect() checkpoint: the second call returns only new events
 * 12. inspect() returns empty after disable()
 */
fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== KBDebug API Test ======")

    var allPass = false
    runBlocking {
        val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
        KBrowser.initializeConfig(storageDir)
        initializeKBrowser()
        println("[Test] CefApp 初始化完成")
        delay(3000)

        val page = KBrowser.newPage(viewportWidth = 1280, viewportHeight = 720)
        val webView = page.webView
        val debug = webView.debug

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

        println("\n[Test] === 5. navigation detected ===")
        val pass5 = insp4.navigated && insp4.currentUrl.contains("httpbin.org/get")
        println("[Test] navigated: ${insp4.navigated}")
        println("[Test] currentUrl: ${insp4.currentUrl}")
        println("[Test] ${if (pass5) "PASS" else "FAIL"} navigation detected")

        // setTimeout so evaluateJavascript doesn't block on the modal alert()
        println("\n[Test] === 6. dialog (alert) ===")
        page.evaluateJavascript("setTimeout(function() { alert('test alert message'); }, 0)")
        delay(1000)

        val insp6 = debug.inspect()
        println("[Test] activeDialog: ${insp6.activeDialog}")
        val pass6 = insp6.activeDialog != null &&
            insp6.activeDialog!!.type == DialogType.ALERT &&
            insp6.activeDialog!!.message.contains("test alert")
        println("[Test] ${if (pass6) "PASS" else "FAIL"} alert dialog captured")

        println("\n[Test] === 7. respondDialog (dismiss alert) ===")
        val dismissResult = debug.respondDialog(accept = false)
        delay(500)
        println("[Test] respondDialog returned: $dismissResult")
        val insp7 = debug.inspect()
        val pass7 = dismissResult && insp7.activeDialog == null
        println("[Test] activeDialog after dismiss: ${insp7.activeDialog}")
        println("[Test] ${if (pass7) "PASS" else "FAIL"} respondDialog dismisses alert")

        // setTimeout so evaluateJavascript doesn't block on the modal confirm()
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

        println("\n[Test] === 8. snapshot() ===")
        val snap = debug.snapshot()
        println("[Test] jsHeapUsedSize: ${snap.jsHeapUsedSize}")
        println("[Test] domNodeCount: ${snap.domNodeCount}")
        println("[Test] consoleErrorCount: ${snap.consoleErrorCount}")
        println("[Test] jsExceptionCount: ${snap.jsExceptionCount}")
        println("[Test] totalRequestCount: ${snap.totalRequestCount}")
        val pass8 = snap.jsHeapUsedSize > 0 && snap.domNodeCount > 0
        println("[Test] ${if (pass8) "PASS" else "FAIL"} snapshot")

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

        println("\n[Test] === 10. executeCdp() ===")
        val cdpResult = debug.executeCdp("Runtime.evaluate", """{"expression":"1+1"}""")
        println("[Test] CDP result: $cdpResult")
        val pass10 = cdpResult != null && cdpResult.contains("2")
        println("[Test] ${if (pass10) "PASS" else "FAIL"} executeCdp")

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

        println("\n[Test] === 12. disable() ===")
        debug.disable()
        val insp12 = debug.inspect()
        val pass12 = insp12.errors.isEmpty() && insp12.requests.isEmpty() && !insp12.navigated
        println("[Test] inspect after disable — errors: ${insp12.errors.size}, requests: ${insp12.requests.size}")
        println("[Test] ${if (pass12) "PASS" else "FAIL"} disable")

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
        allPass = results.all { it.second }
        println("====== ${if (allPass) "ALL PASS" else "SOME FAILED"} ======")

        page.close()
        KBrowser.shutdown()
    }
    if (!allPass) exitProcess(1)
}
