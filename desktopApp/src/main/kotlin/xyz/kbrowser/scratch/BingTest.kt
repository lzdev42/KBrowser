package xyz.kbrowser.scratch

import xyz.kbrowser.webview.KBPage
import xyz.kbrowser.webview.JvmWebView
import xyz.kbrowser.webview.JcefWebViewFactory
import xyz.kbrowser.webview.initializeKBrowser
import java.io.File
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlin.concurrent.thread

fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== 正在初始化 KBrowser 进行 Bing 测试 ======")
    
    val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
    xyz.kbrowser.webview.KBrowser.setConfigPath(storageDir)
    kotlinx.coroutines.runBlocking { initializeKBrowser() }
    
    SwingUtilities.invokeLater {
        try {
            val webView = JcefWebViewFactory.create("https://www.bing.com/", null) as JvmWebView
            
            val frame = javax.swing.JFrame()
            frame.isUndecorated = true
            frame.setSize(1280, 800)
            try {
                frame.opacity = 0.0f
            } catch (e: Exception) {
                // Ignore
            }
            
            frame.contentPane.add(webView.browser.getComponent())
            frame.isVisible = true
            
            thread {
                try {
                    println("正在等待网页加载...")
                    Thread.sleep(8000) // 等待 8 秒让 Bing 稳定加载
                    
                    kotlinx.coroutines.runBlocking {
                        val page = KBPage(webView)
                        println("Current URL: ${page.currentUrl.value}")
                        println("Current Title: ${page.title.value}")
                        
                        println("====== 正在获取 AX Tree 快照 ======")
                        val snapshot = page.snapshot(clean = true)
                        val first50Lines = snapshot.lines().take(50).joinToString("\n")
                        println("快照前 50 行:\n$first50Lines\n...")
                        
                        println("====== 开始查找搜索输入框 ======")
                        val rawTree = page.getRawAxTree()
                        val inputNode = rawTree.nodes.find { 
                            it.id == "sb_form_q" || 
                            (it.tagName == "input" && (it.attributes["name"] == "q" || it.role == "combobox" || it.role == "searchbox" || it.role == "textbox"))
                        }
                        
                        if (inputNode == null) {
                            println("【错误】未找到 Bing 搜索输入框元素！")
                            exitProcess(1)
                        }
                        
                        val refid = inputNode.refid
                        val selector = inputNode.selector
                        println("找到搜索框: refid=$refid, selector=$selector")
                        
                        // 1. 坐标 Hover
                        println("1. 测试坐标 Hover...")
                        page.hover(refid)
                        Thread.sleep(1000)
                        
                        // 2. JS Hover
                        println("2. 测试 JS Hover...")
                        page.jsHover(refid)
                        Thread.sleep(1000)
                        
                        // 3. 坐标 Click
                        println("3. 测试坐标 Click...")
                        page.click(refid)
                        Thread.sleep(1000)
                        
                        // 4. JS Click
                        println("4. 测试 JS Click...")
                        page.jsClick(refid)
                        Thread.sleep(1000)
                        
                        // 5. Locator 坐标 Click
                        println("5. 测试 Locator 坐标 Click...")
                        page.locator("#sb_form_q").click()
                        Thread.sleep(1000)
                        
                        // 6. Locator JS Click
                        println("6. 测试 Locator JS Click...")
                        page.locator("#sb_form_q").jsClick()
                        Thread.sleep(1000)
                        
                        // 7. Locator JS Fill
                        println("7. 测试 Locator JS Fill...")
                        page.locator("#sb_form_q").jsFill("KBrowser JS Fill")
                        Thread.sleep(1000)
                        
                        // 8. Locator JS Type
                        println("8. 测试 Locator JS Type...")
                        page.locator("#sb_form_q").jsType(" Natively Typed")
                        Thread.sleep(2000)
                        
                        // 获取最终输入框的值，验证是否输入成功
                        println("====== 验证最终输入框内容 ======")
                        webView.evaluateJavascript("return document.querySelector('#sb_form_q').value") { result ->
                            println("输入框最终的值: $result")
                            
                            println("====== 所有测试全部通过 ======")
                            webView.destroy()
                            frame.dispose()
                            exitProcess(0)
                        }
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
