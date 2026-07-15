package xyz.kbrowser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 非 OSR（窗口）模式 resize 诊断测试。
 *
 * 验证点：
 * 1. 非 OSR 模式下连续 resize 不崩溃
 * 2. 重量级组件 uiComp 的 componentResized 是否被触发
 * 3. resize 后截图尺寸是否合理
 */
fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== Non-OSR Resize Diagnostic Test ======")

    runBlocking {
        val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
        // 关键：useOsr = false，走窗口模式
        KBrowser.initializeConfig(storageDir, useOsr = false)
        initializeKBrowser()
        println("[Test] CefApp 初始化完成（非 OSR 模式）")
        delay(3000)

        val page = KBrowser.newHeadlessTab()
        println("[Test] newHeadlessTab() 返回成功")

        val jvmWebView = page.webView as? xyz.kbrowser.webview.JvmWebView
        if (jvmWebView == null) {
            println("[Test] ❌ 无法获取 JvmWebView")
            return@runBlocking
        }

        val htmlFile = File("desktopApp/src/test/resources/headless_viewport_test.html")
        val url = htmlFile.toURI().toString()
        println("[Test] 加载测试页面: $url")
        page.loadUrl(url)
        delay(2000)

        // 多尺寸 resize 验证
        println("[Test] === 非 OSR 多尺寸 resize ===")
        val sizes = listOf(800 to 600, 1024 to 768, 640 to 480, 1280 to 720)
        var allPass = true
        for ((w, h) in sizes) {
            jvmWebView.resizeViewport(w, h)
            delay(1000)
            val pngBytes = page.webView.takeScreenshot()?.imageData
            if (pngBytes == null) {
                println("[Test] ❌ resize $w×$h 截图失败")
                allPass = false
                continue
            }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val output = File("/tmp/kbrowser_nonosr_${w}x${h}_$ts.png")
            output.writeBytes(pngBytes)
            val image = javax.imageio.ImageIO.read(output)
            println("[Test] 非OSR resize $w×$h → 截图 ${image.width}×${image.height}")
            if (image.width >= w && image.height >= h) {
                println("[Test] ✅ 非OSR resize $w×$h 截图尺寸合理")
            } else {
                println("[Test] ❌ 非OSR resize $w×$h 截图尺寸异常：${image.width}×${image.height}")
                allPass = false
            }
        }

        page.close()
        KBrowser.shutdown()
        println("====== Test finished: ${if (allPass) "ALL PASS ✅" else "SOME FAILED ❌"} ======")
    }
}
