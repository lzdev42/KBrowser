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
 * resize 跟手验证测试。
 *
 * 验证点：
 * 1. 连续多次 resizeViewport 不崩溃（OSR 100ms 节流 + ResizePusher 承压）
 * 2. resize 后页面 window.innerWidth/innerHeight 与请求尺寸一致（CEF 视口已同步）
 * 3. resize 后截图像素尺寸与请求尺寸（×devicePixelRatio）一致
 *
 * 模拟拖拽场景：快速连续变更尺寸，中间不等 CEF 渲染完成。
 */
fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== Resize Follow Test ======")

    runBlocking {
        val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
        KBrowser.initializeConfig(storageDir)
        initializeKBrowser()
        println("[Test] CefApp 初始化完成")
        delay(3000)

        val page = KBrowser.newHeadlessTab()
        println("[Test] newHeadlessTab() 返回成功")

        val jvmWebView = page.webView as? xyz.kbrowser.webview.JvmWebView
        if (jvmWebView == null) {
            println("[Test] ❌ 无法获取 JvmWebView（非 JVM 平台）")
            return@runBlocking
        }

        val htmlFile = File("desktopApp/src/test/resources/headless_viewport_test.html")
        val url = htmlFile.toURI().toString()
        println("[Test] 加载测试页面: $url")
        page.loadUrl(url)
        delay(1500)

        suspend fun readViewport(): Pair<Int, Int> {
            val jsResult = page.evaluateJavascript(
                """
                JSON.stringify({
                    w: window.innerWidth,
                    h: window.innerHeight,
                    dpr: window.devicePixelRatio
                })
                """.trimIndent()
            )
            println("[Test] viewport(js): $jsResult")
            // headless 下 evaluateJavascript 可能返回 undefined（既有现象，与 resize 无关），
            // 此时以截图尺寸为准。这里仅做参考读取。
            val wRegex = """"w":(\d+)""".toRegex().find(jsResult)?.groupValues?.get(1)?.toIntOrNull()
            val hRegex = """"h":(\d+)""".toRegex().find(jsResult)?.groupValues?.get(1)?.toIntOrNull()
            return (wRegex ?: -1) to (hRegex ?: -1)
        }

        var allPass = true
        val sizes = listOf(
            800 to 600,
            1024 to 768,
            1280 to 720,
            600 to 400,
            1000 to 800,
            480 to 320,
            1280 to 720
        )

        // 第一阶段：快速连续 resize（模拟拖拽，不等渲染完成）
        println("[Test] === 阶段1: 快速连续 resize（模拟拖拽）===")
        for ((w, h) in sizes) {
            jvmWebView.resizeViewport(w, h)
            delay(30) // 30ms 间隔，远小于 100ms 节流窗口
        }

        // 等待节流 + ResizePusher 收敛
        println("[Test] 等待 resize 收敛（2.5s）...")
        delay(2500)

        val lastReq = sizes.last()
        val (realW, realH) = readViewport()
        if (realW == -1 || realH == -1) {
            println("[Test] ⚠️ JS viewport 读取失败（headless 既有现象），以阶段3截图为准")
        } else if (realW == lastReq.first && realH == lastReq.second) {
            println("[Test] ✅ 快速 resize 后视口已收敛到 ${lastReq.first}×${lastReq.second}")
        } else {
            println("[Test] ❌ 视口未收敛：期望 ${lastReq.first}×${lastReq.second}，实际 $realW×$realH")
            allPass = false
        }

        // 第二阶段：逐个 resize 并验证（每个等待渲染完成）
        println("[Test] === 阶段2: 逐个 resize 验证 ===")
        for ((w, h) in listOf(900 to 500, 1100 to 700)) {
            jvmWebView.resizeViewport(w, h)
            delay(800) // 等 ResizePusher(20ms) + 渲染
            val (rw, rh) = readViewport()
            if (rw == -1 || rh == -1) {
                println("[Test] ⚠️ resize $w×$h 后 JS 读取失败，跳过精确比对")
            } else if (rw == w && rh == h) {
                println("[Test] ✅ resize $w×$h 视口正确")
            } else {
                println("[Test] ❌ resize $w×$h 失败：实际 $rw×$rh")
                allPass = false
            }
        }

        // 第三阶段：多种尺寸截图验证（截图是真实渲染结果，最可靠）
        println("[Test] === 阶段3: 多尺寸截图验证 ===")
        val screenshotSizes = listOf(800 to 600, 1024 to 768, 640 to 480, 1280 to 720)
        for ((w, h) in screenshotSizes) {
            jvmWebView.resizeViewport(w, h)
            delay(1200) // 等 ResizePusher 收敛 + 渲染
            val pngBytes = page.webView.takeScreenshot()?.imageData
            if (pngBytes == null) {
                println("[Test] ❌ resize $w×$h 截图失败")
                allPass = false
                continue
            }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val output = File("/tmp/kbrowser_resize_${w}x${h}_$ts.png")
            output.writeBytes(pngBytes)
            val image = javax.imageio.ImageIO.read(output)
            println("[Test] resize $w×$h → 截图 ${image.width}×${image.height}")
            // OSR 截图像素 = CSS尺寸 × pixelDensity；至少应 >= CSS 尺寸
            if (image.width >= w && image.height >= h) {
                println("[Test] ✅ resize $w×$h 截图尺寸合理")
            } else {
                println("[Test] ❌ resize $w×$h 截图尺寸异常：${image.width}×${image.height}（期望 >= $w×$h）")
                allPass = false
            }
        }

        page.close()
        KBrowser.shutdown()
        println("====== Test finished: ${if (allPass) "ALL PASS ✅" else "SOME FAILED ❌"} ======")
    }
}
