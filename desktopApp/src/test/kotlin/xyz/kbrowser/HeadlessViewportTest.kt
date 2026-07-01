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
 * Headless 截图 viewport 验证测试。
 *
 * 1. 用 newHeadlessTab() 创建后台 page（默认 1280×720）
 * 2. 加载本地测试页，读取 window.innerWidth/Height/devicePixelRatio
 * 3. 截图并保存到文件
 * 4. 输出图片路径与关键尺寸信息，供人工目视检查
 */
fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== Headless Viewport Screenshot Test ======")

    runBlocking {
        val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
        KBrowser.initializeConfig(storageDir)
        initializeKBrowser()
        println("[Test] CefApp 初始化完成")
        delay(3000)

        val page = KBrowser.newHeadlessTab()
        println("[Test] newHeadlessTab() 返回成功")

        val htmlFile = File("desktopApp/src/test/resources/headless_viewport_test.html")
        val url = htmlFile.toURI().toString()
        println("[Test] 加载测试页面: $url")
        page.loadUrl(url) // suspend，返回时加载完成

        // 读取页面 viewport 信息
        val jsResult = page.evaluateJavascript(
            """
            JSON.stringify({
                innerWidth: window.innerWidth,
                innerHeight: window.innerHeight,
                devicePixelRatio: window.devicePixelRatio,
                readyState: document.readyState
            })
            """.trimIndent()
        )
        println("[Test] 页面 viewport 信息: $jsResult")

        // 截图
        println("[Test] 正在截图...")
        val pngBytes = page.webView.takeScreenshot()?.imageData
        if (pngBytes == null) {
            println("[Test] ❌ 截图失败")
            return@runBlocking
        }

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val output = File("/tmp/kbrowser_headless_viewport_$ts.png")
        output.writeBytes(pngBytes)
        println("[Test] ✅ 截图已保存: ${output.absolutePath} (${pngBytes.size} bytes)")

        // 额外用 Java 读图尺寸
        val image = javax.imageio.ImageIO.read(output)
        println("[Test] 图片像素尺寸: ${image.width} × ${image.height}")

        // 关闭 page
        page.close()
        KBrowser.shutdown()
        println("====== Test finished ======")
    }
}
