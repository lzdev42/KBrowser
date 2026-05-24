package xyz.kbrowser

import xyz.kbrowser.webview.JvmWebViewController
import java.io.File
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlin.concurrent.thread

fun main() {
    // 强制关闭新版 JCEF 的 Chrome Runtime 原生窗口模式，强制使用纯渲染 Alloy 模式
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== 正在初始化 KBrowser 离屏渲染 (OSR) 模式 ======")
    
    // 关键修复：不能用 runBlocking 阻塞主线程。macOS 和 JCEF 必须依赖主线程或 AWT 的 Event Dispatch Thread 来派发跨进程消息！
    SwingUtilities.invokeLater {
        val controller = JvmWebViewController("https://bot.sannysoft.com/", isOsr = true)
        
        // 关键修复：JCEF 的 OSR 模式（特别是 Alloy 渲染引擎）必须要求 AWT Component 被挂载到一个真实窗口上，
        // 只有触发了底层系统的 addNotify()，底层的 OpenGL/Metal 渲染管线才会真正开始工作，网页才能被加载。
        val frame = javax.swing.JFrame()
        frame.isUndecorated = true
        frame.setSize(1000, 800) // 给它一个正常的渲染尺寸
        frame.opacity = 0.0f // 通过透明度 0 让它隐身，不需要把它移出屏幕
        frame.contentPane.add(controller.browser.getComponent())
        frame.isVisible = true // 必须设置为 true 才能激活渲染！
        
        // 开一个后台线程去等待，千万别阻塞当前的 AWT UI 线程
        thread {
            println("正在等待网页加载并执行 JS...")
            Thread.sleep(15000)
            
            println("====== 开始提取 Aria 语义快照 ======")
            controller.getSemanticSnapshot { ariaJson ->
                val ariaFile = File("aria_snapshot.json")
                ariaFile.writeText(ariaJson)
                println("Aria 提取成功！已保存到 ${ariaFile.absolutePath} (长度: ${ariaJson.length})")
                
                println("====== 开始提取 CSS 选择器 ======")
                controller.getSelectors { selectorsJson ->
                    val selFile = File("selectors.json")
                    selFile.writeText(selectorsJson)
                    println("选择器提取成功！已保存到 ${selFile.absolutePath} (长度: ${selectorsJson.length})")
                    
                    println("====== 开始提取 HTML 源码 ======")
                    controller.getOuterHtml { html ->
                        val htmlFile = File("page.html")
                        htmlFile.writeText(html)
                        println("HTML 提取成功！已保存到 ${htmlFile.absolutePath} (长度: ${html.length})")
                        
                        println("====== 测试完成，正在销毁浏览器 ======")
                        controller.destroy()
                        exitProcess(0)
                    }
                }
            }
        }
    }
}
