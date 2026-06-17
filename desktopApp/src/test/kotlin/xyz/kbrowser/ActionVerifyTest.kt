package xyz.kbrowser.webview

import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * 独立测试：验证 CDP 坐标操作后，能否通过只读 JS API 程序化判断操作是否成功。
 *
 * 验证策略：
 * 1. Click → document.elementFromPoint(x, y) + 业务回调变量
 * 2. Fill/Type → el.value 读回对比
 * 3. Scroll → el.scrollTop 对比
 * 4. Occlusion → elementFromPoint 返回 overlay 而非目标按钮
 */
fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("\n========================================================")
    println("       Action Verification Test (Independent)          ")
    println("========================================================\n")

    thread(name = "action-verify-test") {
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

                val profile = KBProfile("action_verify_profile", "$storageDir/action_verify_profile")
                val webView = JvmWebView(null, profile = profile, isHeadless = false)

                SwingUtilities.invokeLater {
                    frame = JFrame("Action Verify Test").apply {
                        defaultCloseOperation = JFrame.EXIT_ON_CLOSE
                        setSize(1280, 900)
                        contentPane.add(webView.browser.getComponent())
                        isVisible = true
                    }
                }
                delay(2000)

                val page = KBPage(webView)
                KBrowser.registerPage(page)

                // ── 加载本地测试页面 ──
                val htmlFile = File("desktopApp/src/test/resources/action_verify_test.html")
                val url = htmlFile.toURI().toString()
                println("[Test] 加载测试页面: $url")
                page.loadUrl(url)
                println("[Test] 等待页面加载 (3s)...")
                delay(3000)

                // ── 获取 AX Tree ──
                println("\n[Test] 获取 AX Tree snapshot...")
                val snapshot = page.snapshot(clean = true)
                println("[Test] Snapshot 前 600 字符:\n${snapshot.take(600)}\n")

                // ──────────────────────────────────────────────
                // 测试 1: Click 验证 — elementFromPoint
                // ──────────────────────────────────────────────
                println("\n═══ 测试 1: Click 验证 ═══")

                // 1a. 找到 testButton 节点
                val buttonNode = findNodeByRefId(page, "testButton")
                if (buttonNode != null) {
                    println("  找到 testButton: center=(${buttonNode.centerX}, ${buttonNode.centerY})")

                    // 验证前置：elementFromPoint 预判
                    val preCheckJs = """
                        (function() {
                            var el = document.elementFromPoint(${buttonNode.centerX}, ${buttonNode.centerY});
                            return el ? el.id : 'null';
                        })()
                    """.trimIndent()
                    val preCheckResult = page.evaluateJavascript(preCheckJs)
                    println("  [前置检查] elementFromPoint 返回: $preCheckResult")
                    record("1a. elementFromPoint 预判找到 testButton", preCheckResult.contains("testButton"))

                    // 执行点击
                    page.clickByCoordinates(buttonNode.centerX, buttonNode.centerY)
                    delay(500)

                    // 验证后置：检查 JS 回调变量
                    val clickCountJs = "String(clickCount)"
                    val clickCount = page.evaluateJavascript(clickCountJs)
                    println("  [后置验证] clickCount = $clickCount")
                    record("1b. 点击后 clickCount > 0", clickCount.trim().toIntOrNull()?.let { it > 0 } ?: false)
                } else {
                    println("  ⚠️ 未找到 testButton 节点，尝试用 CSS 选择器定位...")
                    // fallback: 用 JS 获取坐标
                    val btnRectJs = """
                        (function() {
                            var el = document.getElementById('testButton');
                            if (!el) return 'not_found';
                            var r = el.getBoundingClientRect();
                            return Math.round(r.x + r.width/2 + window.scrollX) + ',' + Math.round(r.y + r.height/2 + window.scrollY);
                        })()
                    """.trimIndent()
                    val rect = page.evaluateJavascript(btnRectJs)
                    println("  [fallback] testButton 坐标: $rect")
                    if (rect.contains(",")) {
                        val parts = rect.split(",").map { it.trim().toInt() }
                        page.clickByCoordinates(parts[0], parts[1])
                        delay(500)
                        val clickCount = page.evaluateJavascript("String(clickCount)")
                        record("1b. 点击后 clickCount > 0 (fallback)", clickCount.trim().toIntOrNull()?.let { it > 0 } ?: false)
                    }
                }

                // ──────────────────────────────────────────────
                // 测试 2: Fill/Type 验证 — el.value 读回
                // ──────────────────────────────────────────────
                println("\n═══ 测试 2: Fill/Type 验证 ═══")

                // 2a. 用 locator fill
                val inputLocator = page.locator("#testInput")
                val testValue = "Hello KBrowser!"
                inputLocator.fill(testValue)
                delay(500)

                // 验证：读回 el.value
                val valueJs = "document.getElementById('testInput').value"
                val actualValue = page.evaluateJavascript(valueJs)
                println("  [验证] fill('$testValue') → el.value = '$actualValue'")
                record("2a. fill 后 el.value 匹配", actualValue.trim() == testValue)

                // 2b. 用 locator type (逐字符)
                val typeValue = "Typed Text 123"
                inputLocator.type(typeValue)
                delay(2000) // type 是逐字符的，需要更多时间

                val actualTypedValue = page.evaluateJavascript(valueJs)
                println("  [验证] type('$typeValue') → el.value = '$actualTypedValue'")
                record("2b. type 后 el.value 匹配", actualTypedValue.trim() == typeValue)

                // ──────────────────────────────────────────────
                // 测试 3: Scroll 验证 — el.scrollTop 对比
                // ──────────────────────────────────────────────
                println("\n═══ 测试 3: Scroll 验证 ═══")

                // 获取 scroll area 的初始 scrollTop
                val scrollAreaLocator = page.locator("#scrollArea")
                val scrollTopBeforeJs = "String(document.getElementById('scrollArea').scrollTop)"
                val scrollTopBefore = page.evaluateJavascript(scrollTopBeforeJs).trim().toDoubleOrNull() ?: 0.0
                println("  [初始] scrollArea.scrollTop = $scrollTopBefore")

                // 执行滚动
                scrollAreaLocator.scroll(0, 150)
                delay(500)

                // 验证：scrollTop 变化
                val scrollTopAfter = page.evaluateJavascript(scrollTopBeforeJs).trim().toDoubleOrNull() ?: 0.0
                println("  [验证] scroll(0, 150) → scrollTop = $scrollTopAfter")
                record("3a. scroll 后 scrollTop 变化", scrollTopAfter > scrollTopBefore)

                // ──────────────────────────────────────────────
                // 测试 4: Occlusion 遮挡检测
                // ──────────────────────────────────────────────
                println("\n═══ 测试 4: Occlusion 遮挡检测 ═══")

                val occludedNode = findNodeByRefId(page, "occludedButton")
                if (occludedNode != null) {
                    println("  找到 occludedButton: center=(${occludedNode.centerX}, ${occludedNode.centerY})")

                    // 检查 elementFromPoint 返回什么
                    val occlusionJs = """
                        (function() {
                            var el = document.elementFromPoint(${occludedNode.centerX}, ${occludedNode.centerY});
                            return el ? el.id : 'null';
                        })()
                    """.trimIndent()
                    val occlusionResult = page.evaluateJavascript(occlusionJs)
                    println("  [遮挡检查] elementFromPoint 返回: $occlusionResult")
                    val isOccluded = !occlusionResult.contains("occludedButton")
                    record("4a. 遮挡检测: elementFromPoint 不返回被遮挡元素", isOccluded)
                    println("  → 该元素被 ${if (isOccluded) "遮挡 (符合预期)" else "未遮挡 (意外)"}")
                } else {
                    // fallback
                    val occRectJs = """
                        (function() {
                            var el = document.getElementById('occludedButton');
                            if (!el) return 'not_found';
                            var r = el.getBoundingClientRect();
                            return Math.round(r.x + r.width/2) + ',' + Math.round(r.y + r.height/2);
                        })()
                    """.trimIndent()
                    val occRect = page.evaluateJavascript(occRectJs)
                    println("  [fallback] occludedButton 坐标: $occRect")
                    if (occRect.contains(",")) {
                        val parts = occRect.split(",").map { it.trim().toInt() }
                        val occlusionJs = """
                            (function() {
                                var el = document.elementFromPoint(${parts[0]}, ${parts[1]});
                                return el ? el.id : 'null';
                            })()
                        """.trimIndent()
                        val occlusionResult = page.evaluateJavascript(occlusionJs)
                        println("  [遮挡检查] elementFromPoint 返回: $occlusionResult")
                        record("4a. 遮挡检测 (fallback)", !occlusionResult.contains("occludedButton"))
                    }
                }

                // ──────────────────────────────────────────────
                // 测试 5: 远端元素 — scrollIntoView + click
                // ──────────────────────────────────────────────
                println("\n═══ 测试 5: 远端元素 scrollIntoView + click ═══")

                val farNode = findNodeByRefId(page, "farButton")
                if (farNode != null) {
                    println("  找到 farButton: center=(${farNode.centerX}, ${farNode.centerY})")

                    // 点击远端元素（clickByCoordinates 内部会自动 scrollIntoView）
                    page.clickByCoordinates(farNode.centerX, farNode.centerY)
                    delay(800)

                    val farClickCount = page.evaluateJavascript("String(farClickCount)")
                    println("  [验证] farClickCount = $farClickCount")
                    record("5a. 远端元素点击成功 (自动 scrollIntoView)", farClickCount.trim().toIntOrNull()?.let { it > 0 } ?: false)
                } else {
                    println("  ⚠️ 未找到 farButton 节点")
                    record("5a. 远端元素点击", false)
                }

                // ──────────────────────────────────────────────
                // 测试 6: 综合验证函数 __getVerifyData
                // ──────────────────────────────────────────────
                println("\n═══ 测试 6: 综合验证数据一次性获取 ═══")
                val verifyData = page.evaluateJavascript("window.__getVerifyData()")
                println("  综合数据: $verifyData")
                record("6a. __getVerifyData() 返回有效 JSON", try { Json.parseToJsonElement(verifyData); true } catch (e: Exception) { false })

                // ──────────────────────────────────────────────
                // 测试 7: OperationResult API 验证
                // ──────────────────────────────────────────────
                println("\n═══ 测试 7: OperationResult API 返回值验证 ═══")

                // 7a. clickByCoordinates 返回 OperationResult
                val btnNode2 = findNodeByRefId(page, "testButton")
                if (btnNode2 != null) {
                    val clickResult = page.clickByCoordinates(btnNode2.centerX, btnNode2.centerY)
                    println("  [7a] clickByCoordinates → $clickResult")
                    record("7a. clickByCoordinates 返回 Success", clickResult is OperationResult.Success)
                }

                // 7b. locator.fill 返回 OperationResult
                val fillResult = page.locator("#testInput").fill("OperationResult Test")
                println("  [7b] locator.fill → $fillResult")
                record("7b. locator.fill 返回 Success", fillResult is OperationResult.Success)

                // 7c. locator.scroll 返回 OperationResult
                val scrollResult = page.locator("#scrollArea").scroll(0, 80)
                println("  [7c] locator.scroll → $scrollResult")
                record("7c. locator.scroll 返回 Success", scrollResult is OperationResult.Success)

                // 7d. 遮挡元素: 用 page.click(refid) 才有 targetNode 信息做遮挡检测
                val treeData = page.getRawAxTree()
                val occAxNode = treeData.nodes.find {
                    it.id == "occludedButton" || it.selector.contains("occludedButton")
                }
                if (occAxNode != null) {
                    val occClickResult = page.click(occAxNode.refid)
                    println("  [7d] 遮挡元素 page.click(refid) → $occClickResult")
                    val isFailure = occClickResult is OperationResult.Failure
                    println("  → 结果: ${if (isFailure) "Failure (符合预期)" else "Success (意外)"}")
                    record("7d. 遮挡元素返回 Failure", isFailure)
                } else {
                    println("  ⚠️ 未找到 occludedButton 的 AxNode，跳过遮挡测试")
                    record("7d. 遮挡元素返回 Failure", false)
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

/**
 * 辅助函数：从 AX tree 中查找包含指定 id 属性值的节点。
 * 在 AxNode 的 `name` 或 `description` 中匹配，或者直接用 refid 匹配。
 * 这里我们用一个简单的 JS fallback 来获取元素的文档坐标。
 */
private suspend fun findNodeByRefId(page: KBPage, elementId: String): NodeInfo? {
    // 使用 JS 获取元素文档坐标（更可靠）
    val js = """
        (function() {
            var el = document.getElementById('$elementId');
            if (!el) return 'not_found';
            var r = el.getBoundingClientRect();
            var x = Math.round(r.x + r.width/2 + window.scrollX);
            var y = Math.round(r.y + r.height/2 + window.scrollY);
            return x + ',' + y;
        })()
    """.trimIndent()
    val result = page.evaluateJavascript(js).trim()
    if (result == "not_found" || !result.contains(",")) return null
    val parts = result.split(",").map { it.toIntOrNull() ?: return null }
    return NodeInfo(parts[0], parts[1])
}

data class NodeInfo(val centerX: Int, val centerY: Int)
