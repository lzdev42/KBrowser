package xyz.kbrowser.webview

import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("\n========================================================")
    println("       Auto Scroll + Click Test (Independent)          ")
    println("========================================================\n")

    thread(name = "auto-scroll-click-test") {
        runBlocking {
            val results = mutableListOf<Pair<String, Boolean>>()

            fun record(name: String, pass: Boolean) {
                results.add(name to pass)
                val icon = if (pass) "✅ PASS" else "❌ FAIL"
                println("  $icon  $name")
            }

            var frame: JFrame? = null
            try {
                val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
                KBrowser.setConfigPath(storageDir)
                initializeKBrowser()
                println("[Test] CefApp 初始化完成")
                delay(3000)

                val profile = KBProfile("auto_scroll_test_profile", "$storageDir/auto_scroll_test_profile")
                val webView = JvmWebView(null, profile = profile, isHeadless = false)

                SwingUtilities.invokeLater {
                    frame = JFrame("Auto Scroll Click Test").apply {
                        defaultCloseOperation = JFrame.EXIT_ON_CLOSE
                        setSize(1280, 900)
                        contentPane.add(webView.browser.getComponent())
                        isVisible = true
                    }
                }
                delay(2000)

                val page = KBPage(webView)
                KBrowser.registerPage(page)

                val htmlFile = File("desktopApp/src/test/resources/auto_scroll_click_test.html")
                val url = htmlFile.toURI().toString()
                println("[Test] 加载测试页面: $url")
                page.loadUrl(url)
                println("[Test] 等待页面加载 (3s)...")
                delay(3000)

                // ── 前置检查：确认页面初始状态 ──
                println("\n═══ 前置检查 ═══")
                val initialData = page.evaluateJavascript("window.__getScrollClickData()")
                println("  初始数据: $initialData")
                val initialJson = Json.parseToJsonElement(initialData).jsonObject
                val initialScrollY = initialJson["scrollY"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                println("  初始 scrollY = $initialScrollY")
                record("0a. 页面初始 scrollY = 0", initialScrollY == 0.0)

                // ──────────────────────────────────────────────
                // 测试 1: 下方远端按钮 (margin-top: 3000px)
                // ──────────────────────────────────────────────
                println("\n═══ 测试 1: 下方远端按钮 (belowButton) ═══")

                val belowRectJs = """
                    (function() {
                        var el = document.getElementById('belowButton');
                        if (!el) return 'not_found';
                        var r = el.getBoundingClientRect();
                        return JSON.stringify({
                            docX: Math.round(r.x + r.width/2 + window.scrollX),
                            docY: Math.round(r.y + r.height/2 + window.scrollY),
                            vpX: Math.round(r.x + r.width/2),
                            vpY: Math.round(r.y + r.height/2),
                            inViewport: r.top >= 0 && r.left >= 0 && r.bottom <= window.innerHeight && r.right <= window.innerWidth
                        });
                    })()
                """.trimIndent()
                val belowRectStr = page.evaluateJavascript(belowRectJs)
                println("  belowButton 位置信息: $belowRectStr")

                val belowRect = Json.parseToJsonElement(belowRectStr).jsonObject
                val belowDocX = belowRect["docX"]?.jsonPrimitive?.intOrNull ?: 0
                val belowDocY = belowRect["docY"]?.jsonPrimitive?.intOrNull ?: 0
                val belowInVp = belowRect["inViewport"]?.jsonPrimitive?.booleanOrNull ?: true
                println("  belowButton 文档坐标: ($belowDocX, $belowDocY), 在视口内: $belowInVp")
                record("1a. belowButton 初始不在视口内", !belowInVp)

                // 执行点击（clickByCoordinates 内部应自动滚动）
                println("  执行 clickByCoordinates($belowDocX, $belowDocY)...")
                val belowClickResult = page.clickByCoordinates(belowDocX, belowDocY)
                println("  clickByCoordinates 返回: $belowClickResult")
                delay(800)

                // 验证：1) 页面是否滚动了  2) 按钮是否被点击
                val afterBelowData = page.evaluateJavascript("window.__getScrollClickData()")
                val afterBelowJson = Json.parseToJsonElement(afterBelowData).jsonObject
                val afterBelowScrollY = afterBelowJson["scrollY"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val belowClicked = afterBelowJson["belowClicked"]?.jsonPrimitive?.intOrNull ?: 0
                println("  点击后 scrollY = $afterBelowScrollY, belowClicked = $belowClicked")
                record("1b. 点击后页面已滚动 (scrollY > 0)", afterBelowScrollY > 0)
                record("1c. belowButton 被成功点击 (belowClicked > 0)", belowClicked > 0)

                // ──────────────────────────────────────────────
                // 测试 2: 右侧远端按钮 (margin-left: 2000px)
                // ──────────────────────────────────────────────
                println("\n═══ 测试 2: 右侧远端按钮 (rightButton) ═══")

                // 先滚回顶部
                page.evaluateJavascript("window.scrollTo(0, 0)")
                delay(500)

                val rightRectJs = """
                    (function() {
                        var el = document.getElementById('rightButton');
                        if (!el) return 'not_found';
                        var r = el.getBoundingClientRect();
                        return JSON.stringify({
                            docX: Math.round(r.x + r.width/2 + window.scrollX),
                            docY: Math.round(r.y + r.height/2 + window.scrollY),
                            inViewport: r.top >= 0 && r.left >= 0 && r.bottom <= window.innerHeight && r.right <= window.innerWidth
                        });
                    })()
                """.trimIndent()
                val rightRectStr = page.evaluateJavascript(rightRectJs)
                println("  rightButton 位置信息: $rightRectStr")

                val rightRect = Json.parseToJsonElement(rightRectStr).jsonObject
                val rightDocX = rightRect["docX"]?.jsonPrimitive?.intOrNull ?: 0
                val rightDocY = rightRect["docY"]?.jsonPrimitive?.intOrNull ?: 0
                val rightInVp = rightRect["inViewport"]?.jsonPrimitive?.booleanOrNull ?: true
                println("  rightButton 文档坐标: ($rightDocX, $rightDocY), 在视口内: $rightInVp")
                record("2a. rightButton 初始不在视口内", !rightInVp)

                println("  执行 clickByCoordinates($rightDocX, $rightDocY)...")
                val rightClickResult = page.clickByCoordinates(rightDocX, rightDocY)
                println("  clickByCoordinates 返回: $rightClickResult")
                delay(800)

                val afterRightData = page.evaluateJavascript("window.__getScrollClickData()")
                val afterRightJson = Json.parseToJsonElement(afterRightData).jsonObject
                val afterRightScrollX = afterRightJson["scrollX"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val rightClicked = afterRightJson["rightClicked"]?.jsonPrimitive?.intOrNull ?: 0
                println("  点击后 scrollX = $afterRightScrollX, rightClicked = $rightClicked")
                record("2b. 点击后页面已水平滚动 (scrollX > 0)", afterRightScrollX > 0)
                record("2c. rightButton 被成功点击 (rightClicked > 0)", rightClicked > 0)

                // ──────────────────────────────────────────────
                // 测试 3: 对角远端按钮 (margin-top: 2500px, margin-left: 2500px)
                // ──────────────────────────────────────────────
                println("\n═══ 测试 3: 对角远端按钮 (diagonalButton) ═══")

                page.evaluateJavascript("window.scrollTo(0, 0)")
                delay(500)

                val diagRectJs = """
                    (function() {
                        var el = document.getElementById('diagonalButton');
                        if (!el) return 'not_found';
                        var r = el.getBoundingClientRect();
                        return JSON.stringify({
                            docX: Math.round(r.x + r.width/2 + window.scrollX),
                            docY: Math.round(r.y + r.height/2 + window.scrollY),
                            inViewport: r.top >= 0 && r.left >= 0 && r.bottom <= window.innerHeight && r.right <= window.innerWidth
                        });
                    })()
                """.trimIndent()
                val diagRectStr = page.evaluateJavascript(diagRectJs)
                println("  diagonalButton 位置信息: $diagRectStr")

                val diagRect = Json.parseToJsonElement(diagRectStr).jsonObject
                val diagDocX = diagRect["docX"]?.jsonPrimitive?.intOrNull ?: 0
                val diagDocY = diagRect["docY"]?.jsonPrimitive?.intOrNull ?: 0
                val diagInVp = diagRect["inViewport"]?.jsonPrimitive?.booleanOrNull ?: true
                println("  diagonalButton 文档坐标: ($diagDocX, $diagDocY), 在视口内: $diagInVp")
                record("3a. diagonalButton 初始不在视口内", !diagInVp)

                println("  执行 clickByCoordinates($diagDocX, $diagDocY)...")
                val diagClickResult = page.clickByCoordinates(diagDocX, diagDocY)
                println("  clickByCoordinates 返回: $diagClickResult")
                delay(800)

                val afterDiagData = page.evaluateJavascript("window.__getScrollClickData()")
                val afterDiagJson = Json.parseToJsonElement(afterDiagData).jsonObject
                val afterDiagScrollX = afterDiagJson["scrollX"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val afterDiagScrollY = afterDiagJson["scrollY"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val diagClicked = afterDiagJson["diagonalClicked"]?.jsonPrimitive?.intOrNull ?: 0
                println("  点击后 scrollX=$afterDiagScrollX, scrollY=$afterDiagScrollY, diagonalClicked=$diagClicked")
                record("3b. 点击后页面已双向滚动", afterDiagScrollX > 0 && afterDiagScrollY > 0)
                record("3c. diagonalButton 被成功点击 (diagonalClicked > 0)", diagClicked > 0)

                // ──────────────────────────────────────────────
                // 测试 4: 使用 locator.click() 点击远端元素
                // ──────────────────────────────────────────────
                println("\n═══ 测试 4: 通过 locator.click() 点击远端元素 ═══")

                page.evaluateJavascript("window.scrollTo(0, 0)")
                delay(500)

                page.evaluateJavascript("clickLog.below = 0; updateStatus();")

                val belowLocator = page.locator("#belowButton")
                val locatorClickResult = belowLocator.click()
                println("  locator.click() 返回: $locatorClickResult")
                delay(800)

                val afterLocatorData = page.evaluateJavascript("window.__getScrollClickData()")
                val afterLocatorJson = Json.parseToJsonElement(afterLocatorData).jsonObject
                val locatorBelowClicked = afterLocatorJson["belowClicked"]?.jsonPrimitive?.intOrNull ?: 0
                val locatorScrollY = afterLocatorJson["scrollY"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                println("  locator 点击后 belowClicked=$locatorBelowClicked, scrollY=$locatorScrollY")
                record("4a. locator.click() 成功点击远端元素", locatorBelowClicked > 0)
                record("4b. locator.click() 后页面已滚动", locatorScrollY > 0)

                // ── 打印总结 ──
                println("\n========================================================")
                println("                     测试结果总结                       ")
                println("========================================================")
                val passed = results.count { it.second }
                val total = results.size
                results.forEach { (name, pass) ->
                    println("  ${if (pass) "✅" else "❌"} $name")
                }
                println("────────────────────────────────────────────────────────")
                println("  总计: $passed / $total 通过")
                println("========================================================")

            } catch (e: Exception) {
                println("[Test] ❌ 执行过程中发生错误:")
                e.printStackTrace()
            } finally {
                println("\n[Test] 测试完毕，释放资源...")
                KBrowser.shutdown()
                frame?.dispose()
                exitProcess(0)
            }
        }
    }
}
