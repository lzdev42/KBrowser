package xyz.kbrowser.webview

import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("\n========================================================")
    println("       Popup / Floating Window Scroll Test               ")
    println("========================================================\n")

    thread(name = "popup-scroll-test") {
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
                KBrowser.initializeConfig(storageDir)
                initializeKBrowser()
                println("[Test] CefApp 初始化完成")
                delay(3000)

                val profile = KBProfile("popup_scroll_test_profile", "$storageDir/popup_scroll_test_profile")
                val page = KBrowser.newPage(profile = profile)

                SwingUtilities.invokeLater {
                    frame = JFrame("Popup Scroll Test").apply {
                        defaultCloseOperation = JFrame.EXIT_ON_CLOSE
                        setSize(1280, 900)
                        contentPane.add((page.webView as JvmWebView).browser.getComponent())
                        isVisible = true
                    }
                }
                delay(2000)

                val htmlFile = File("desktopApp/src/test/resources/popup_scroll_test.html")
                val url = htmlFile.toURI().toString()
                println("[Test] 加载测试页面: $url")
                page.loadUrl(url)
                println("[Test] 等待页面加载 (3s)...")
                delay(3000)

                println("\n═══ 前置检查 ═══")
                val initialData = withTimeout(5000) {
                    page.evaluateJavascript("window.__getPopupScrollTestData()")
                }
                println("  初始数据: $initialData")

                val initialJson = Json.parseToJsonElement(initialData).jsonObject
                val initialPopupScrollTop = initialJson["popupScrollTop"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val popupTargetBtnVisible = initialJson["popupTargetBtnVisible"]?.jsonPrimitive?.booleanOrNull ?: false
                val mainPageScrollY = initialJson["mainPageScrollY"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                println("  initialPopupScrollTop=$initialPopupScrollTop, popupTargetBtnVisible=$popupTargetBtnVisible, mainPageScrollY=$mainPageScrollY")
                record("0a. 页面初始 popupScrollTop = 0", initialPopupScrollTop == 0.0)
                record("0b. 目标按钮初始不可见", !popupTargetBtnVisible)

                // ──────────────────────────────────────────────
                // 测试 1: 点击浮窗内需要滚动的目标按钮
                // ──────────────────────────────────────────────
                println("\n═══ 测试 1: 点击浮窗内需要滚动的目标按钮 (popupTargetBtn) ═══")

                val snap1 = withTimeout(120000) { page.snapshot() }
                val dialogNodes = snap1.rawTree.nodes.filter { it.role == "dialog" || it.role == "alertdialog" }
                println("  AX tree dialog nodes: ${dialogNodes.map { "refid=${it.refid} role=${it.role} id=${it.id}" }}")

                val targetBtn = snap1.rawTree.nodes.firstOrNull { it.id == "popupTargetBtn" && it.isVisible }
                if (targetBtn == null) {
                    println("  popupTargetBtn 未找到或不可见，跳过")
                    record("1a. popupTargetBtn 初始在浮窗视口外 (不可见)", false)
                    record("1b. 浮窗内列表已滚动 (popupScrollTop > 0)", false)
                    record("1c. popupTargetBtn 变为可见", false)
                    record("1d. popupTargetBtn 被成功点击", false)
                } else {
                    println("  popupTargetBtn: refid=${targetBtn.refid} center=(${targetBtn.centerX},${targetBtn.centerY})")

                    val popup = dialogNodes.firstOrNull()
                    println("  popup dialog: ${popup?.let { "refid=${it.refid} selector=${it.selector}" } ?: "NOT FOUND"}")

                    val popupBody = withTimeout(5000) {
                        page.evaluateJavascript("JSON.stringify({scrollTop: document.getElementById('popupBody').scrollTop, scrollHeight: document.getElementById('popupBody').scrollHeight, clientHeight: document.getElementById('popupBody').clientHeight, targetVisible: (function(){var t=document.getElementById('popupTargetBtn').getBoundingClientRect();var p=document.getElementById('popupBody').getBoundingClientRect();return t.top>=p.top&&t.bottom<=p.bottom})()})")
                    }
                    val beforeJson = Json.parseToJsonElement(popupBody).jsonObject
                    val beforePopupScrollTop = beforeJson["scrollTop"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val beforeTargetVisible = beforeJson["targetVisible"]?.jsonPrimitive?.booleanOrNull ?: false
                    println("  before: popupScrollTop=$beforePopupScrollTop, targetVisible=$beforeTargetVisible")
                    record("1a. popupTargetBtn 初始在浮窗视口外 (不可见)", !beforeTargetVisible)

                    println("  执行 page.click(refid=${targetBtn.refid})...")
                    val clickResult = withTimeout(10000) { page.click(targetBtn.refid) }
                    println("  click 返回: $clickResult")
                    delay(800)

                    val afterData = withTimeout(5000) {
                        page.evaluateJavascript("JSON.stringify({scrollTop: document.getElementById('popupBody').scrollTop, targetVisible: (function(){var t=document.getElementById('popupTargetBtn').getBoundingClientRect();var p=document.getElementById('popupBody').getBoundingClientRect();return t.top>=p.top&&t.bottom<=p.bottom})(), clicked: window.__getPopupScrollTestData ? JSON.parse(window.__getPopupScrollTestData()).popupTargetBtnClicked : false})")
                    }
                    val afterJson = Json.parseToJsonElement(afterData).jsonObject
                    val afterPopupScrollTop = afterJson["scrollTop"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val afterTargetVisible = afterJson["targetVisible"]?.jsonPrimitive?.booleanOrNull ?: false
                    val afterClicked = afterJson["clicked"]?.jsonPrimitive?.booleanOrNull ?: false
                    println("  after: popupScrollTop=$afterPopupScrollTop, targetVisible=$afterTargetVisible, clicked=$afterClicked")
                    record("1b. 浮窗内列表已滚动 (popupScrollTop > 0)", afterPopupScrollTop > 0)
                    record("1c. popupTargetBtn 变为可见", afterTargetVisible)
                    record("1d. popupTargetBtn 被成功点击", afterClicked)
                }

                // ──────────────────────────────────────────────
                // 测试 2: 主页面远端元素（验证向后兼容）
                // ──────────────────────────────────────────────
                println("\n═══ 测试 2: 主页面远端元素 (向后兼容) ═══")

                withTimeout(5000) {
                    page.evaluateJavascript("(function() { window.scrollTo(0, 0); clickState.lastItem = 'none'; updateStatus(); return 'ok'; })()")
                }
                delay(500)

                val mainBtnRectJs = """
                    (function() {
                        var btn = document.getElementById('mainPageFarBtn');
                        if (!btn) return 'not_found';
                        var r = btn.getBoundingClientRect();
                        return JSON.stringify({
                            docX: Math.round(r.x + r.width / 2 + window.scrollX),
                            docY: Math.round(r.y + r.height / 2 + window.scrollY),
                            vpX: Math.round(r.x + r.width / 2),
                            vpY: Math.round(r.y + r.height / 2),
                            inViewport: r.top >= 0 && r.bottom <= window.innerHeight
                        });
                    })()
                """.trimIndent()
                val mainBtnRectStr = withTimeout(5000) { page.evaluateJavascript(mainBtnRectJs) }
                println("  mainPageFarBtn 位置信息: $mainBtnRectStr")

                val mainBtnRect = Json.parseToJsonElement(mainBtnRectStr).jsonObject
                val mainBtnDocX = mainBtnRect["docX"]?.jsonPrimitive?.intOrNull ?: 0
                val mainBtnDocY = mainBtnRect["docY"]?.jsonPrimitive?.intOrNull ?: 0
                val mainBtnInVp = mainBtnRect["inViewport"]?.jsonPrimitive?.booleanOrNull ?: true
                println("  mainPageFarBtn 文档坐标: ($mainBtnDocX, $mainBtnDocY), 在视口内: $mainBtnInVp")
                record("2a. mainPageFarBtn 初始不在视口内", !mainBtnInVp)

                println("  执行 clickByCoordinates($mainBtnDocX, $mainBtnDocY)...")
                val mainBtnClickResult = withTimeout(10000) { page.clickByCoordinates(mainBtnDocX, mainBtnDocY) }
                println("  clickByCoordinates 返回: $mainBtnClickResult")
                delay(800)

                val afterMainData = withTimeout(5000) {
                    page.evaluateJavascript("window.__getPopupScrollTestData()")
                }
                val afterMainJson = Json.parseToJsonElement(afterMainData).jsonObject
                val afterMainScrollY = afterMainJson["mainPageScrollY"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val afterMainBtnClicked = afterMainJson["mainPageFarBtnClicked"]?.jsonPrimitive?.booleanOrNull ?: false

                val afterMainBtnDebug = withTimeout(5000) {
                    page.evaluateJavascript("""
                        (function() {
                            var btn = document.getElementById('mainPageFarBtn');
                            if (!btn) return 'not_found';
                            var r = btn.getBoundingClientRect();
                            var elAtPoint = document.elementFromPoint(${mainBtnDocX}, ${mainBtnDocY} - window.scrollY);
                            return JSON.stringify({
                                btnVpX: Math.round(r.x + r.width / 2),
                                btnVpY: Math.round(r.y + r.height / 2),
                                btnW: Math.round(r.width),
                                btnH: Math.round(r.height),
                                scrollY: Math.round(window.scrollY),
                                clickVpX: ${mainBtnDocX},
                                clickVpY: ${mainBtnDocY} - Math.round(window.scrollY),
                                elAtClickPoint: elAtPoint ? elAtPoint.tagName + (elAtPoint.id ? '#' + elAtPoint.id : '') + (elAtPoint.className ? '.' + elAtPoint.className : '') : 'null'
                            });
                        })()
                    """)
                }
                println("  滚动后按钮调试信息: $afterMainBtnDebug")
                println("  afterMainScrollY=$afterMainScrollY, mainPageFarBtnClicked=$afterMainBtnClicked")
                record("2b. 主页面已滚动 (mainPageScrollY > 0)", afterMainScrollY > 0)
                record("2c. mainPageFarBtn 被成功点击", afterMainBtnClicked)

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
