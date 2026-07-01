package xyz.kbrowser

import kotlinx.coroutines.runBlocking
import xyz.kbrowser.webview.*
import kotlin.system.exitProcess

fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== JS Dialog / Handler Repro ======")

    runBlocking {
        try {
            KBrowser.initializeConfig(System.getProperty("user.home") + "/.browserpilot/jcef_cache", useOsr = true)
            initializeKBrowser()

            val page = KBrowser.newHeadlessTab()
            val webView = page.webView

            var alertMessage: String? = null
            var promptMessage: String? = null
            var promptDefault: String? = null
            var confirmMessage: String? = null

            webView.setWebChromeClient(object : KBWebChromeClient {
                override fun onJsAlert(url: String, message: String, callback: JsResultCallback) {
                    alertMessage = message
                    println("[ALERT] url=$url")
                    println("[ALERT] message(len=${message.length})=${message.take(200)}")
                    callback.confirm()
                }
                override fun onJsConfirm(url: String, message: String, callback: JsResultCallback) {
                    confirmMessage = message
                    println("[CONFIRM] message=$message")
                    callback.confirm()
                }
                override fun onJsPrompt(url: String, message: String, defaultValue: String?, callback: JsPromptResultCallback) {
                    promptMessage = message
                    promptDefault = defaultValue
                    println("[PROMPT] message=$message default=$defaultValue")
                    callback.confirm("test-input")
                }
                override fun onPermissionRequest(request: PermissionRequest) {
                    println("[PERMISSION] origin=${request.origin} resources=${request.resources}")
                    request.grant()
                }
            })

            val html = """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"></head><body>
            <button id="btnAlert" onclick="alert('这是一条 Alert 消息!')">alert</button>
            <button id="btnConfirm" onclick="confirm('确认?')">confirm</button>
            <button id="btnPrompt" onclick="prompt('请输入名字','默认值')">prompt</button>
            <div id="result"></div>
            </body></html>
            """.trimIndent()

            webView.loadHtml(html)

            // Wait for load
            Thread.sleep(1000)

            // Test alert
            webView.evaluateJavascript("document.getElementById('btnAlert').click();", null)
            Thread.sleep(500)

            // Test confirm
            webView.evaluateJavascript("document.getElementById('btnConfirm').click();", null)
            Thread.sleep(500)

            // Test prompt
            webView.evaluateJavascript("document.getElementById('btnPrompt').click();", null)
            Thread.sleep(500)

            // Test registerJsHandler
            webView.registerJsHandler("getConfig") { request ->
                println("[HANDLER] request=$request")
                "{\"theme\":\"dark\",\"version\":\"1.0\"}"
            }

            var handlerResult: String? = null
            webView.evaluateJavascript("""
                (function() {
                    window.getConfig('{"key":"theme"}').then(function(r) {
                        document.getElementById('result').textContent = 'HANDLER:' + r;
                        console.log('[KB_HANDLER_OK] ' + r);
                    }).catch(function(e) {
                        document.getElementById('result').textContent = 'ERROR:' + e.message;
                        console.log('[KB_HANDLER_ERR] ' + e.message);
                    });
                    return 'invoked';
                })()
            """.trimIndent()) { result ->
                handlerResult = result
                println("[HANDLER RESULT] $result")
            }
            Thread.sleep(1000)
            webView.evaluateJavascript("document.getElementById('result').textContent") { result ->
                println("[HANDLER DOM RESULT] $result")
            }
            Thread.sleep(1000)

            println("====== Summary ======")
            println("alertMessage=${alertMessage?.take(100)}")
            println("confirmMessage=${confirmMessage}")
            println("promptMessage=${promptMessage} default=${promptDefault}")
            println("handlerResult=${handlerResult}")

            page.close()
            KBrowser.shutdown()
            exitProcess(0)
        } catch (e: Exception) {
            e.printStackTrace()
            exitProcess(1)
        }
    }
}
