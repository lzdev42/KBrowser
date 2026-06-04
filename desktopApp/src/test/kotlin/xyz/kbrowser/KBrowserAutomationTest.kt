package xyz.kbrowser

import kotlinx.coroutines.runBlocking
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.KBProfile
import kotlin.system.exitProcess

fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== KBrowser 自动化测试 ======")

    runBlocking {
        try {
            val profile = KBProfile("test_isolation_profile", System.getProperty("user.home") + "/.browserpilot/jcef_cache")

            println("[测试] 正在使用 KBrowser 新建页面...")
            val page = KBrowser.newPage("https://example.com")

            println("[测试] 正在等待网页加载...")
            page.loadUrl("https://example.com")
            println("[测试] 网页加载成功！")
            println("[测试] URL: ${page.currentUrl.value}")
            println("[测试] Title: ${page.title.value}")

            println("[测试] 正在销毁全局引擎...")
            KBrowser.shutdown()
            println("====== 测试顺利通过 ======")
            exitProcess(0)
        } catch (e: Exception) {
            println("[测试] ❌ 执行出错:")
            e.printStackTrace()
            exitProcess(1)
        }
    }
}
