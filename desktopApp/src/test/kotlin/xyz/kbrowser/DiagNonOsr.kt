package xyz.kbrowser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

fun main() {
    runBlocking {
        val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
        KBrowser.initializeConfig(storageDir, useOsr = false)
        initializeKBrowser()

        val page = KBrowser.newPage()
        val jvm = page.webView as xyz.kbrowser.webview.JvmWebView
        val cefBrowser = jvm.browser.getCefBrowser()

        SwingUtilities.invokeLater {
            JFrame("Diag").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                setSize(1280, 800)
                contentPane.add(jvm.browser.getComponent())
                isVisible = true
            }
            page.webView.loadHtml("<html><body><h1 id='m'>DIAG_OK</h1></body></html>")
        }

        repeat(15) { i ->
            delay(1000)
            val dt = try { cefBrowser.devToolsClient != null } catch (e: Exception) { "ERR:${e.message?.take(60)}" }
            val url = try { page.evaluateJavascript("window.location.href") } catch (e: Exception) { "ERR:${e.message?.take(60)}" }
            val marker = try { page.evaluateJavascript("document.getElementById('m')?.textContent || 'MISSING'") } catch (e: Exception) { "ERR" }
            println("[Diag ${i}s] devTools=$dt url=$url marker=$marker loading=${page.webView.loadingState.value}")
        }
        KBrowser.shutdown()
        exitProcess(0)
    }
}
