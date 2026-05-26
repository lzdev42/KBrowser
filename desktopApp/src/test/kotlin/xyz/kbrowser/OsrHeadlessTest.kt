package xyz.kbrowser

import xyz.kbrowser.webview.JcefWebViewFactory
import xyz.kbrowser.webview.JvmWebView
import xyz.kbrowser.webview.initializeKBrowser
import java.io.File
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlin.concurrent.thread

fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== OSR 无头渲染测试 ======")

    initializeKBrowser()

    SwingUtilities.invokeLater {
        val webView = JcefWebViewFactory.create("https://bot.sannysoft.com/", null)
        val jvmWebView = webView as JvmWebView

        val frame = javax.swing.JFrame()
        frame.isUndecorated = true
        frame.setSize(1000, 800)
        try { frame.opacity = 0.0f } catch (e: Exception) {}
        frame.contentPane.add(jvmWebView.browser.getComponent())
        frame.isVisible = true

        thread {
            println("正在等待网页加载...")
            Thread.sleep(15000)

            println("====== 提取 HTML 源码 ======")
            webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                val htmlFile = File("page.html")
                htmlFile.writeText(html)
                println("HTML 提取成功！长度: ${html.length}")

                println("====== 测试完成，销毁浏览器 ======")
                webView.destroy()
                frame.dispose()
                exitProcess(0)
            }
        }
    }
}
