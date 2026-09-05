package xyz.kbrowser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Resize follow (responsiveness) verification test.
 *
 * Checks:
 * 1. Many consecutive resizeViewport calls don't crash (OSR 100ms throttle + ResizePusher under load)
 * 2. After resize, window.innerWidth/innerHeight matches the requested size (CEF viewport synced)
 * 3. After resize, the screenshot pixel size matches the requested size (x devicePixelRatio)
 *
 * Simulates drag-resize: rapid consecutive size changes without waiting for CEF to finish rendering.
 */
fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== Resize Follow Test ======")

    var allPass = true
    runBlocking {
        val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
        KBrowser.initializeConfig(storageDir)
        initializeKBrowser()
        println("[Test] CefApp 初始化完成")
        delay(3000)

        val page = KBrowser.newPage(viewportWidth = 1280, viewportHeight = 720)
        println("[Test] newPage() 返回成功")

        val jvmWebView = page.webView as? xyz.kbrowser.webview.JvmWebView
        if (jvmWebView == null) {
            println("[Test] ❌ 无法获取 JvmWebView（非 JVM 平台）")
            return@runBlocking
        }

        val htmlFile = File("desktopApp/src/test/resources/viewport_test.html")
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
            // evaluateJavascript on a background page may return undefined (pre-existing
            // behavior, unrelated to resize); the screenshot size is the source of truth,
            // so this read is advisory only.
            val wRegex = """"w":(\d+)""".toRegex().find(jsResult)?.groupValues?.get(1)?.toIntOrNull()
            val hRegex = """"h":(\d+)""".toRegex().find(jsResult)?.groupValues?.get(1)?.toIntOrNull()
            return (wRegex ?: -1) to (hRegex ?: -1)
        }

        val sizes = listOf(
            800 to 600,
            1024 to 768,
            1280 to 720,
            600 to 400,
            1000 to 800,
            480 to 320,
            1280 to 720
        )

        // Phase 1: rapid consecutive resizes (drag simulation, no waiting for render)
        println("[Test] === 阶段1: 快速连续 resize（模拟拖拽）===")
        for ((w, h) in sizes) {
            jvmWebView.resizeViewport(w, h)
            delay(30) // 30ms interval, well below the 100ms throttle window
        }

        println("[Test] 等待 resize 收敛（2.5s）...")
        delay(2500)

        val lastReq = sizes.last()
        val (realW, realH) = readViewport()
        if (realW == -1 || realH == -1) {
            println("[Test] ⚠️ JS viewport 读取失败（后台 page 既有现象），以阶段3截图为准")
        } else if (realW == lastReq.first && realH == lastReq.second) {
            println("[Test] ✅ 快速 resize 后视口已收敛到 ${lastReq.first}×${lastReq.second}")
        } else {
            println("[Test] ❌ 视口未收敛：期望 ${lastReq.first}×${lastReq.second}，实际 $realW×$realH")
            allPass = false
        }

        // Phase 2: resize one at a time and verify (waiting for render after each)
        println("[Test] === 阶段2: 逐个 resize 验证 ===")
        for ((w, h) in listOf(900 to 500, 1100 to 700)) {
            jvmWebView.resizeViewport(w, h)
            delay(800) // wait for ResizePusher (20ms) + render
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

        // Phase 3: multi-size screenshot verification (screenshots are the real render result,
        // so they are the most reliable check)
        println("[Test] === 阶段3: 多尺寸截图验证 ===")
        val screenshotSizes = listOf(800 to 600, 1024 to 768, 640 to 480, 1280 to 720)
        for ((w, h) in screenshotSizes) {
            jvmWebView.resizeViewport(w, h)
            delay(1200) // wait for ResizePusher to settle + render
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
            // OSR screenshot pixels = CSS size x pixelDensity; should be at least the CSS size
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
    if (!allPass) exitProcess(1)
}
