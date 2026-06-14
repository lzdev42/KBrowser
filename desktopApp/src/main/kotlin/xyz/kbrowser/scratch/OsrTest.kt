package xyz.kbrowser.scratch

import xyz.kbrowser.webview.KBProfile
import xyz.kbrowser.webview.JcefWebViewFactory
import xyz.kbrowser.webview.initializeKBrowser
import java.io.File
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlin.concurrent.thread

fun main() {
    // 强制关闭新版 JCEF 的 Chrome Runtime 原生窗口模式，强制使用纯渲染 Alloy 模式
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== 正在初始化 KBrowser 离屏渲染 (OSR) 模式 ======")
    
    val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
    xyz.kbrowser.webview.KBrowser.setConfigPath(storageDir)
    kotlinx.coroutines.runBlocking { initializeKBrowser() }
    
    SwingUtilities.invokeLater {
        try {
            val webView = JcefWebViewFactory.create("https://bot.sannysoft.com/", null)
            
            // JCEF 的 OSR 模式（特别是 Alloy 渲染引擎）必须要求 AWT Component 被挂载到一个真实窗口上
            val frame = javax.swing.JFrame()
            frame.isUndecorated = true
            frame.setSize(1000, 800)
            try {
                frame.opacity = 0.0f
            } catch (e: Exception) {
                // Ignore
            }
            
            val jvmWebView = webView as xyz.kbrowser.webview.JvmWebView
            frame.contentPane.add(jvmWebView.browser.getComponent())
            frame.isVisible = true
            
            thread {
                try {
                    println("正在等待网页加载...")
                    Thread.sleep(10000)
                    
                    println("====== 开始读取网页 URL 和 Title ======")
                    println("Current URL: ${webView.currentUrl.value}")
                    println("Current Title: ${webView.currentTitle.value}")
                    println("Loading State: ${webView.loadingState.value}")
                    
                    println("====== 正在获取 HTML 源码 ======")
                    webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                        println("HTML 长度: ${html.length}")
                        println("====== 销毁浏览器 ======")
                        webView.destroy()
                        frame.dispose()
                        exitProcess(0)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    exitProcess(1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            exitProcess(1)
        }
    }
}
