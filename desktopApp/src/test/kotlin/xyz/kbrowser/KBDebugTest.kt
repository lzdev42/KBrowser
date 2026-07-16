package xyz.kbrowser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.debug.ConsoleLevel
import xyz.kbrowser.webview.debug.DebugEvent
import xyz.kbrowser.webview.initializeKBrowser

/**
 * KBDebug API 测试。
 *
 * 验证点：
 * 1. enable() 后 CDP 连接成功，devToolsClient 可用
 * 2. console.log/error 被捕获到事件流
 * 3. JS 异常被捕获
 * 4. 网络请求被追踪（requestWillBeSent → loadingFinished）
 * 5. snapshot() 返回正确的性能指标和错误计数
 * 6. getResponseBody() 能取回响应体
 * 7. executeCdp() 原始 CDP 调用可用
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
        println("[Test] newHeadlessTab() 返回成功")

        val webView = page.webView
        val debug = webView.debug

        // 1. enable
        println("\n[Test] === 1. enable() ===")
        debug.enable()
        delay(2000)

        // 2. console events
        println("\n[Test] === 2. console events ===")
        page.evaluateJavascript(
            """
            console.log('test log message');
            console.error('test error message');
            console.warn('test warn message');
            """.trimIndent()
        )
        delay(1000)

        val consoleEvents = debug.events.replayCache
            .filterIsInstance<DebugEvent.ConsoleLog>()
        println("[Test] console events captured: ${consoleEvents.size}")
        consoleEvents.forEach { e ->
            println("[Test]   [${e.level}] ${e.text}")
        }
        val pass2 = consoleEvents.size >= 3 &&
            consoleEvents.any { it.level == ConsoleLevel.LOG && it.text.contains("test log") } &&
            consoleEvents.any { it.level == ConsoleLevel.ERROR && it.text.contains("test error") } &&
            consoleEvents.any { it.level == ConsoleLevel.WARNING && it.text.contains("test warn") }
        println("[Test] ${if (pass2) "✅" else "❌"} console events")

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

        val excEvents = debug.events.replayCache
            .filterIsInstance<DebugEvent.JsException>()
        println("[Test] JS exception events captured: ${excEvents.size}")
        excEvents.forEach { e ->
            println("[Test]   ${e.message}")
        }
        val pass3 = excEvents.isNotEmpty() && excEvents.any { it.message.contains("test exception") }
        println("[Test] ${if (pass3) "✅" else "❌"} JS exception")

        // 4. network requests
        println("\n[Test] === 4. network requests ===")
        page.loadUrl("https://httpbin.org/get")
        delay(3000)

        val netEvents = debug.events.replayCache
            .filterIsInstance<DebugEvent.NetworkRequest>()
        println("[Test] network events captured: ${netEvents.size}")
        netEvents.take(5).forEach { e ->
            println("[Test]   ${e.method} ${e.status ?: "?"} ${e.url.take(80)}")
        }
        val pass4 = netEvents.isNotEmpty()
        println("[Test] ${if (pass4) "✅" else "❌"} network requests")

        // 5. snapshot
        println("\n[Test] === 5. snapshot() ===")
        val snap = debug.snapshot()
        println("[Test] jsHeapUsedSize: ${snap.jsHeapUsedSize}")
        println("[Test] jsHeapTotalSize: ${snap.jsHeapTotalSize}")
        println("[Test] domNodeCount: ${snap.domNodeCount}")
        println("[Test] jsEventListeners: ${snap.jsEventListeners}")
        println("[Test] documents: ${snap.documents}")
        println("[Test] consoleErrorCount: ${snap.consoleErrorCount}")
        println("[Test] jsExceptionCount: ${snap.jsExceptionCount}")
        println("[Test] failedRequestCount: ${snap.failedRequestCount}")
        println("[Test] totalRequestCount: ${snap.totalRequestCount}")
        println("[Test] crashedRecently: ${snap.crashedRecently}")
        println("[Test] recentErrors count: ${snap.recentErrors.size}")
        val pass5 = snap.jsHeapUsedSize > 0 && snap.domNodeCount > 0
        println("[Test] ${if (pass5) "✅" else "❌"} snapshot")

        // 6. getResponseBody
        println("\n[Test] === 6. getResponseBody() ===")
        val firstReqId = netEvents.firstOrNull()?.requestId
        val pass6: Boolean
        if (firstReqId != null) {
            val body = debug.getResponseBody(firstReqId)
            println("[Test] response body length: ${body?.length ?: 0}")
            println("[Test] response body preview: ${body?.take(200)}")
            pass6 = body != null && body.isNotEmpty()
            println("[Test] ${if (pass6) "✅" else "❌"} getResponseBody")
        } else {
            pass6 = false
            println("[Test] ⚠️ no network request to test getResponseBody")
        }

        // 7. executeCdp
        println("\n[Test] === 7. executeCdp() ===")
        val cdpResult = debug.executeCdp("Runtime.evaluate", """{"expression":"1+1"}""")
        println("[Test] CDP Runtime.evaluate result: $cdpResult")
        val pass7 = cdpResult != null && cdpResult.contains("2")
        println("[Test] ${if (pass7) "✅" else "❌"} executeCdp")

        // 8. disable
        println("\n[Test] === 8. disable() ===")
        debug.disable()
        println("[Test] disabled, events replayCache should be cleared")
        val afterDisable = debug.events.replayCache
        println("[Test] replayCache size after disable: ${afterDisable.size}")
        val pass8 = afterDisable.isEmpty()
        println("[Test] ${if (pass8) "✅" else "❌"} disable clears replay cache")

        // Summary
        println("\n====== Test Summary ======")
        val results = listOf(
            "console events" to pass2,
            "JS exception" to pass3,
            "network requests" to pass4,
            "snapshot" to pass5,
            "getResponseBody" to pass6,
            "executeCdp" to pass7,
            "disable" to pass8
        )
        results.forEach { (name, pass) ->
            println("  ${if (pass) "✅" else "❌"} $name")
        }
        val allPass = listOf(pass2, pass3, pass4, pass5, pass6, pass7, pass8).all { it }
        println("====== ${if (allPass) "ALL PASS ✅" else "SOME FAILED ❌"} ======")

        page.close()
        KBrowser.shutdown()
    }
}
