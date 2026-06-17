package xyz.kbrowser

import xyz.kbrowser.jcef.KBCefAxTreeFetcher
import xyz.kbrowser.webview.*
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * CDP AXTree 测试 — bing.com + zhipin.com
 *
 * 直接 run main() 即可。
 * 输出文件保存在项目根目录：
 *   axtree_raw_<site>.json      — CDP getFullAXTree 原始响应
 *   axtree_nodes_<site>.txt     — 处理后节点摘要
 */
fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")

    val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
    KBrowser.setConfigPath(storageDir)
    kotlinx.coroutines.runBlocking { initializeKBrowser() }

    // macOS 上 startupAsync 是异步的，initializeKBrowser 里的 10s 等待可能不够
    // 额外再等 5s 确保 CefApp 完全初始化
    println("[main] 额外等待 CefApp 完全初始化 (5s)...")
    Thread.sleep(5000)

    println("\n╔══════════════════════════════════════════════════════╗")
    println("║         CDP AXTree 测试  bing + zhipin               ║")
    println("╚══════════════════════════════════════════════════════╝\n")

    val sites = listOf(
        "https://www.bing.com",
        "https://www.zhipin.com"
    )

    SwingUtilities.invokeLater {
        thread(name = "ax-test") {
            for (url in sites) {
                testSite(url)
            }
            println("\n====== 全部测试完成 ======")
            exitProcess(0)
        }
    }
}

private fun testSite(url: String) {
    val siteName = url.removePrefix("https://www.").removeSuffix("/").replace("/", "_")
    println("\n┌─────────────────────────────────────────────────────")
    println("│ 测试: $url")
    println("└─────────────────────────────────────────────────────")

    val webView = JcefWebViewFactory.create(url, null) as JvmWebView
    val frame = javax.swing.JFrame().apply {
        isUndecorated = true
        setSize(1280, 800)
        try { opacity = 0.0f } catch (_: Exception) {}
        contentPane.add(webView.browser.getComponent())
        isVisible = true
    }

    try {
        println("[1] 等待页面加载 (15s)...")
        Thread.sleep(15000)
        println("    URL:   ${webView.currentUrl.value}")
        println("    Title: ${webView.currentTitle.value}")

        val cefBrowser = webView.browser.getCefBrowser()
        val devTools = cefBrowser.devToolsClient

        // ── Step 1: devTools 可用性 ──────────────────────────────────────
        println("\n[2] 检查 devToolsClient...")
        if (devTools == null) { println("    ❌ devToolsClient == null"); return }
        println("    isClosed=${devTools.isClosed}")
        if (devTools.isClosed) { println("    ❌ devToolsClient is closed"); return }
        println("    ✅ devToolsClient 可用")

        // ── Step 2: Runtime.evaluate 基础连通 ───────────────────────────
        println("\n[3] Runtime.evaluate 连通测试...")
        try {
            val result = devTools.executeDevToolsMethod(
                "Runtime.evaluate",
                """{"expression":"document.title","returnByValue":true}"""
            ).get(10, TimeUnit.SECONDS)
            println("    结果: ${result?.take(200)}")
            println("    ✅ Runtime.evaluate 正常")
        } catch (e: Exception) {
            println("    ❌ 失败: ${e::class.simpleName}: ${e.message}")
        }

        // ── Step 3: getFullAXTree 原始响应 ───────────────────────────────
        println("\n[4] Accessibility.getFullAXTree 原始响应...")
        val rawJson: String? = try {
            val t0 = System.currentTimeMillis()
            val json = devTools.executeDevToolsMethod(
                "Accessibility.getFullAXTree", "{}"
            ).get(20, TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - t0
            println("    耗时: ${elapsed}ms  长度: ${json?.length ?: "null"}")
            json
        } catch (e: Exception) {
            println("    ❌ 异常: ${e::class.simpleName}: ${e.message}")
            null
        }

        if (rawJson == null) {
            println("    ❌ 返回 null，跳过后续步骤")
            return
        }

        // 基础格式检查（不依赖 serialization 库，纯字符串）
        val looksLikeJson = rawJson.trimStart().startsWith("{")
        val hasNodes = rawJson.contains("\"nodes\"")
        println("    looksLikeJson=$looksLikeJson  hasNodes=$hasNodes")
        if (!looksLikeJson || !hasNodes) {
            println("    ❌ 响应格式异常，前500字符:")
            println("    ${rawJson.take(500)}")
        } else {
            println("    ✅ 格式正常")
        }

        // 保存原始 JSON
        File("axtree_raw_$siteName.json").writeText(rawJson)
        println("    原始 JSON 已保存: axtree_raw_$siteName.json")

        // ── Step 4: KBCefAxTreeFetcher 完整流程 ─────────────────────────
        println("\n[5] KBCefAxTreeFetcher.fetch() 完整流程...")
        val treeData = try {
            val t0 = System.currentTimeMillis()
            val result = KBCefAxTreeFetcher.fetch(cefBrowser)
            val elapsed = System.currentTimeMillis() - t0
            println("    耗时: ${elapsed}ms")
            result
        } catch (e: Exception) {
            println("    ❌ 异常: ${e::class.simpleName}: ${e.message}")
            null
        }

        if (treeData == null || treeData.nodes.isEmpty()) {
            println("    ❌ 返回空结果: nodes=${treeData?.nodes?.size ?: "null"}")
        } else {
            println("    ✅ 节点总数: ${treeData.nodes.size}")
            println("    可见节点:   ${treeData.visibleElements}")
            println("    viewport:   ${treeData.innerWidth}×${treeData.innerHeight}")
            println("    url:        ${treeData.url}")

            // 打印前20个可见节点
            println("\n    ── 前20个可见节点 ──")
            treeData.nodes.filter { it.isVisible }.take(20).forEach { n ->
                println("    [${n.refid}] role=${n.role.padEnd(12)} tag=${n.tagName.padEnd(8)} " +
                        "pos=(${n.x},${n.y}) size=${n.width}×${n.height}  " +
                        "text='${n.text.take(40)}'")
            }

            // 保存完整节点摘要
            val summary = buildString {
                appendLine("=== $siteName AXTree 节点摘要 ===")
                appendLine("URL: ${treeData.url}")
                appendLine("Viewport: ${treeData.innerWidth}×${treeData.innerHeight}")
                appendLine("Total: ${treeData.totalElements}  Visible: ${treeData.visibleElements}")
                appendLine()
                appendLine("── 所有可见节点 ──")
                treeData.nodes.filter { it.isVisible }.forEach { n ->
                    appendLine("[${n.refid}] role=${n.role.padEnd(15)} tag=${n.tagName.padEnd(8)} " +
                            "pos=(${n.x},${n.y}) size=${n.width}×${n.height}  " +
                            "text='${n.text.take(60)}'")
                }
                appendLine()
                appendLine("── role 类型统计 ──")
                treeData.nodes.groupBy { it.role }.entries
                    .sortedByDescending { it.value.size }
                    .forEach { (role, list) -> appendLine("  ${role.padEnd(20)}: ${list.size}") }
            }
            File("axtree_nodes_$siteName.txt").writeText(summary)
            println("\n    节点摘要已保存: axtree_nodes_$siteName.txt")
        }

        // ── Step 5: 稳定性 — 连续 3 次 ──────────────────────────────────
        println("\n[6] 稳定性测试（连续 3 次 getFullAXTree，间隔 1s）...")
        repeat(3) { i ->
            val t0 = System.currentTimeMillis()
            try {
                val json = devTools.executeDevToolsMethod(
                    "Accessibility.getFullAXTree", "{}"
                ).get(20, TimeUnit.SECONDS)
                val elapsed = System.currentTimeMillis() - t0
                if (json == null) {
                    println("    第${i+1}次: ❌ null  耗时=${elapsed}ms")
                } else {
                    val ok = json.trimStart().startsWith("{") && json.contains("\"nodes\"")
                    val marker = if (ok) "✅" else "❌ 格式异常"
                    println("    第${i+1}次: $marker  长度=${json.length}  耗时=${elapsed}ms")
                    if (!ok) println("    前200字符: ${json.take(200)}")
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - t0
                println("    第${i+1}次: ❌ ${e::class.simpleName}: ${e.message?.take(100)}  耗时=${elapsed}ms")
            }
            Thread.sleep(1000)
        }

    } finally {
        webView.destroy()
        frame.dispose()
        Thread.sleep(2000)
    }
}
