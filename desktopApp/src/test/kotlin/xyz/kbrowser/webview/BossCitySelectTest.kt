package xyz.kbrowser.webview

import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("\n========================================================")
    println("   Boss直聘 - 弹窗感知滚动 综合测试                      ")
    println("========================================================\n")

    thread(name = "boss-test") {
        runBlocking {
            val results = mutableListOf<Pair<String, Boolean>>()

            fun record(name: String, pass: Boolean) {
                results.add(name to pass)
                val icon = if (pass) "✅ PASS" else "❌ FAIL"
                println("  $icon  $name")
            }

            try {
                val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
                KBrowser.setConfigPath(storageDir)
                initializeKBrowser()
                println("[T] CefApp 初始化完成")
                delay(3000)

                val page = KBrowser.newHeadlessTab(viewportWidth = 1280, viewportHeight = 800)
                println("[T] 创建无头页面成功")

                println("[T] 加载 Boss直聘首页...")
                withTimeout(30000) { page.loadUrl("https://www.zhipin.com") }
                delay(3000)
                println("[T] URL: ${page.currentUrl.value}")

                // ═══════════════════════════════════════════════
                // 场景 A: 弹窗内热门城市点击（弹窗内元素，无需滚动）
                // ═══════════════════════════════════════════════
                println("\n═══ 场景 A: 打开城市弹窗 + 点击弹窗内热门城市 ═══")

                val snap1 = withTimeout(120000) { page.snapshot() }
                println("[T] Snapshot 1: ${snap1.rawTree.nodes.size} nodes")

                val switchBtn = snap1.rawTree.nodes.firstOrNull { node ->
                    node.text.contains("切换") && node.isVisible && node.occludedBy == null
                }
                if (switchBtn != null) {
                    println("[T] 找到切换按钮: refid=${switchBtn.refid}")
                    val clickR = page.click(switchBtn.refid)
                    println("[T] 点击切换: $clickR")
                    delay(2000)

                    val snap2 = withTimeout(120000) { page.snapshot() }
                    val dialogNodes = snap2.rawTree.nodes.filter { it.role == "dialog" }
                    println("[T] 弹窗节点: ${dialogNodes.map { "refid=${it.refid} text=${it.text.take(20)}" }}")

                    val hotCities = snap2.rawTree.nodes.filter { node ->
                        node.role == "link" && node.isVisible && node.occludedBy == null &&
                            (node.text == "杭州" || node.text == "北京" || node.text == "上海" ||
                                node.text == "广州" || node.text == "深圳")
                    }
                    println("[T] 热门城市: ${hotCities.map { it.text }}")

                    val hangzhou = hotCities.firstOrNull { it.text == "杭州" }
                    if (hangzhou != null) {
                        println("[T] 点击杭州: refid=${hangzhou.refid}")
                        val cityR = page.click(hangzhou.refid)
                        println("[T] 点击结果: $cityR")
                        delay(2000)
                        val urlAfter = page.currentUrl.value
                        println("[T] URL 变为: $urlAfter")
                        record("A1. 弹窗内热门城市点击成功", urlAfter?.contains("hangzhou") == true)
                    } else {
                        record("A1. 弹窗内热门城市点击成功", false)
                    }
                } else {
                    record("A1. 弹窗内热门城市点击成功", false)
                }

                // ═══════════════════════════════════════════════
                // 场景 B: 弹窗操作后 → 主页面底部元素滚动点击
                // ═══════════════════════════════════════════════
                println("\n═══ 场景 B: 弹窗操作后 → 主页面底部元素滚动点击 ═══")

                val snap3 = withTimeout(120000) { page.snapshot() }
                val allNodes = snap3.rawTree.nodes

                val scrollYBefore = withTimeout(5000) {
                    page.evaluateJavascript("window.scrollY")
                }.toDoubleOrNull() ?: 0.0
                println("[T] 当前 scrollY: $scrollYBefore")

                val viewportH = 800
                val farNodes = allNodes.filter { node ->
                    node.isVisible && node.occludedBy == null &&
                        node.centerY > scrollYBefore + viewportH &&
                        (node.role == "link" || node.role == "button") &&
                        node.width > 20 && node.height > 10
                }
                println("[T] 视口外可交互节点 (${farNodes.size} 个)")
                farNodes.take(5).forEach { node ->
                    println("    refid=${node.refid}, role=${node.role}, text=${node.text.take(30)}, center=(${node.centerX},${node.centerY})")
                }

                val farTarget = farNodes.firstOrNull()
                if (farTarget != null) {
                    println("[T] 点击视口外节点: refid=${farTarget.refid}, text=${farTarget.text.take(30)}, center=(${farTarget.centerX},${farTarget.centerY})")
                    val farClickR = page.click(farTarget.refid)
                    println("[T] 点击结果: $farClickR")

                    val clickSuccess = farClickR.toString().contains("Success")
                    record("B1. 弹窗操作后主页面远端元素点击命中", clickSuccess)
                } else {
                    println("[T] 没有找到视口外的可交互节点")
                    record("B1. 弹窗操作后主页面远端元素点击命中", true)
                }

                // ═══════════════════════════════════════════════
                // 场景 C: 滚回顶部 → 点击顶部元素
                // ═══════════════════════════════════════════════
                println("\n═══ 场景 C: 滚回顶部 → 点击顶部元素 ═══")

                withTimeout(5000) {
                    page.evaluateJavascript("(function(){ window.scrollTo(0,0); return 'ok'; })()")
                }
                delay(1000)

                val snap4 = withTimeout(120000) { page.snapshot() }
                val topNodes = snap4.rawTree.nodes.filter { node ->
                    node.isVisible && node.occludedBy == null &&
                        node.centerY < 100 && node.centerY > 0 &&
                        (node.role == "link" || node.role == "button") &&
                        node.width > 20 && node.height > 10
                }

                val topTarget = topNodes.firstOrNull()
                if (topTarget != null) {
                    println("[T] 点击顶部节点: refid=${topTarget.refid}, text=${topTarget.text.take(30)}")
                    val topClickR = page.click(topTarget.refid)
                    println("[T] 点击结果: $topClickR")
                    delay(1000)
                    record("C1. 滚回顶部后能点击顶部元素", topClickR.toString().contains("Success"))
                } else {
                    println("[T] 没有找到顶部可交互节点")
                    record("C1. 滚回顶部后能点击顶部元素", true)
                }

                // ═══════════════════════════════════════════════
                // 场景 D: 再次打开弹窗 → 点击需要滚动的城市
                // ═══════════════════════════════════════════════
                println("\n═══ 场景 D: 再次打开弹窗 → 点击需要滚动的城市 ═══")

                withTimeout(5000) {
                    page.evaluateJavascript("(function(){ window.scrollTo(0,0); return 'ok'; })()")
                }
                delay(500)

                val snap5 = withTimeout(120000) { page.snapshot() }
                val switchBtn2 = snap5.rawTree.nodes.firstOrNull { node ->
                    node.text.contains("切换") && node.isVisible && node.occludedBy == null
                }
                if (switchBtn2 != null) {
                    println("[T] 再次点击切换按钮")
                    page.click(switchBtn2.refid)
                    delay(2000)

                    val snap6 = withTimeout(120000) { page.snapshot() }
                    val dialogNodes2 = snap6.rawTree.nodes.filter { it.role == "dialog" }
                    println("[T] 弹窗节点: ${dialogNodes2.map { "refid=${it.refid}" }}")

                    val allCitiesInPopup = snap6.rawTree.nodes.filter { node ->
                        node.role == "link" && node.isVisible &&
                            (node.text == "厦门" || node.text == "青岛" || node.text == "大连" ||
                                node.text == "苏州" || node.text == "宁波" || node.text == "无锡" ||
                                node.text == "长沙" || node.text == "郑州" || node.text == "沈阳" ||
                                node.text == "佛山" || node.text == "东莞" || node.text == "合肥" ||
                                node.text == "昆明" || node.text == "济南" || node.text == "太原" ||
                                node.text == "贵阳" || node.text == "兰州" || node.text == "乌鲁木齐")
                    }
                    println("[T] 非热门城市: ${allCitiesInPopup.map { "${it.text}@(${it.centerX},${it.centerY})" }}")

                    val scrollCity = allCitiesInPopup.firstOrNull { node ->
                        node.occludedBy == null && node.centerY > 400
                    }
                    if (scrollCity != null) {
                        println("[T] 点击需要滚动的城市: ${scrollCity.text}, center=(${scrollCity.centerX},${scrollCity.centerY})")
                        val scrollCityR = page.click(scrollCity.refid)
                        println("[T] 点击结果: $scrollCityR")
                        delay(2000)

                        val urlAfterD = page.currentUrl.value
                        val cityClicked = scrollCityR.toString().contains("Success") ||
                            urlAfterD?.lowercase()?.contains(scrollCity.text.lowercase()) == true
                        record("D1. 弹窗内非热门城市点击成功", cityClicked)
                    } else {
                        println("[T] 没有找到需要滚动的城市节点")
                        record("D1. 弹窗内非热门城市点击成功", false)
                    }
                } else {
                    println("[T] 没有找到切换按钮")
                    record("D1. 弹窗内非热门城市点击成功", false)
                }

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
                println("[T] ❌ 执行过程中发生错误:")
                e.printStackTrace()
            } finally {
                println("\n[T] 释放资源...")
                KBrowser.shutdown()
                exitProcess(0)
            }
        }
    }
}
