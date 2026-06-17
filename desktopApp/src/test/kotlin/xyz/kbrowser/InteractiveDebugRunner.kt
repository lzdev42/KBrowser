package xyz.kbrowser.webview

import xyz.kbrowser.jcef.KBCefAxTreeFetcher
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("\n========================================================")
    println("          KBrowser AxTree Snapshot Debug Runner          ")
    println("========================================================\n")

    val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
    KBrowser.setConfigPath(storageDir)
    runBlocking { initializeKBrowser() }

    println("[Runner] 额外等待 CefApp 完全初始化 (5s)...")
    Thread.sleep(5000)

    val profile = KBProfile("debug_profile", "$storageDir/debug_profile")
    val webView = JvmWebView(null, profile = profile, isHeadless = false)

    // 创建有头的 Swing JFrame 窗口
    var frame: JFrame? = null
    SwingUtilities.invokeLater {
        frame = JFrame("KBrowser AxTree Debugger").apply {
            defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            setSize(1280, 800)
            contentPane.add(webView.browser.getComponent())
            isVisible = true
        }
    }

    thread(name = "debug-runner") {
        runBlocking {
            try {
                val page = KBPage(webView)
                KBrowser.registerPage(page)

                // ────────────────────────────────────────────────────────
                // 场景 1: 搜索框遮挡物及其关闭按钮
                // ────────────────────────────────────────────────────────
                println("\n--- [测试场景 1] 加载主页，检测搜索框遮挡物与关闭按钮 ---")
                val url1 = "https://www.zhipin.com/suzhou/?seoRefer=index"
                println("正在导航到: $url1")
                page.loadUrl(url1)
                println("等待页面完全渲染并显示弹窗 (12秒)...")
                Thread.sleep(12000)

                val rawTree1 = page.getRawAxTree()
                val cleanYaml1 = page.snapshot(clean = true)

                // 保存以备分析
                File("debug_raw_1.json").writeText(Json { prettyPrint = true }.encodeToString(AxTreeData.serializer(), rawTree1))
                File("debug_clean_1.yaml").writeText(cleanYaml1)
                println("已保存: debug_raw_1.json & debug_clean_1.yaml")

                // 诊断搜索框遮挡情况
                val searchInput = rawTree1.nodes.firstOrNull { it.tagName == "input" && (it.text.contains("搜索职位") || it.placeholder().contains("搜索职位")) }
                    ?: rawTree1.nodes.firstOrNull { it.placeholder().isNotEmpty() || it.text.contains("搜索") }
                if (searchInput != null) {
                    println("发现搜索框: refid=${searchInput.refid}, tagName=${searchInput.tagName}, text='${searchInput.text}', placeholder='${searchInput.placeholder()}', occludedBy=${searchInput.occludedBy}")
                    if (searchInput.occludedBy != null) {
                        val occluder = rawTree1.nodes.firstOrNull { it.refid == searchInput.occludedBy }
                        if (occluder != null) {
                            println("  -> 遮挡物节点: refid=${occluder.refid}, tagName=${occluder.tagName}, role=${occluder.role}, text='${occluder.text}'")
                        } else {
                            println("  -> 遮挡物 refid=${searchInput.occludedBy} 但在节点列表中未找到")
                        }
                    } else {
                        println("  -> 警告: 搜索框未被检测到遮挡，这可能不符合预期！")
                    }
                } else {
                    println("未找到搜索框节点，打印所有 input 节点:")
                    rawTree1.nodes.filter { it.tagName == "input" }.forEach {
                        println("  [${it.refid}] text='${it.text}' placeholder='${it.placeholder()}' isVisible=${it.isVisible} occludedBy=${it.occludedBy}")
                    }
                }

                // ────────────────────────────────────────────────────────
                // 场景 2: 城市/工作经验按钮及其 hover 菜单项
                // ────────────────────────────────────────────────────────
                println("\n--- [测试场景 2] 加载职位页，Hover 展开工作经验菜单 ---")
                val url2 = "https://www.zhipin.com/web/geek/jobs?query=&city=101190400&industry=&position=&_security_check=1_1780512502562"
                println("正在导航到: $url2")
                page.loadUrl(url2)
                println("等待页面加载 (10秒)...")
                Thread.sleep(10000)

                // 查找“工作经验”或“城市”按钮
                val rawTree2Before = page.getRawAxTree()
                val jobExpBtn = rawTree2Before.nodes.firstOrNull { it.text.contains("工作经验") }
                    ?: rawTree2Before.nodes.firstOrNull { it.text.contains("经验") }
                    ?: rawTree2Before.nodes.firstOrNull { it.tagName == "span" && it.text.isNotEmpty() && it.isVisible }

                if (jobExpBtn != null) {
                    println("找到目标 Hover 按钮: refid=${jobExpBtn.refid}, role=${jobExpBtn.role}, tagName=${jobExpBtn.tagName}, text='${jobExpBtn.text}'")
                    println("正在执行 page.hover(${jobExpBtn.refid})...")
                    page.hover(jobExpBtn.refid)
                    println("等待菜单展开 (4秒)...")
                    Thread.sleep(4000)

                    val rawTree2After = page.getRawAxTree()
                    val cleanYaml2After = page.snapshot(clean = true)

                    File("debug_raw_2_after.json").writeText(Json { prettyPrint = true }.encodeToString(AxTreeData.serializer(), rawTree2After))
                    File("debug_clean_2_after.yaml").writeText(cleanYaml2After)
                    println("已保存: debug_raw_2_after.json & debug_clean_2_after.yaml")

                    // 检查展开的菜单项是否被识别 (例如 在校生、应届生、经验不限 等)
                    val menuItems = rawTree2After.nodes.filter { it.text.contains("在校生") || it.text.contains("应届生") || it.text.contains("1-3年") }
                    println("查找到的菜单项节点数: ${menuItems.size}")
                    menuItems.forEach {
                        println("  [${it.refid}] text='${it.text}' role='${it.role}' tagName='${it.tagName}' isVisible=${it.isVisible} parentId=${rawTree2After.findParentRefId(it.refid)}")
                    }
                } else {
                    println("未找到'工作经验'按钮，打印所有文本非空节点:")
                    rawTree2Before.nodes.filter { it.text.isNotEmpty() && it.isVisible }.take(20).forEach {
                        println("  [${it.refid}] tagName=${it.tagName} text='${it.text}'")
                    }
                }

                // ────────────────────────────────────────────────────────
                // 场景 3: 扫码登录和各种按钮
                // ────────────────────────────────────────────────────────
                println("\n--- [测试场景 3] 加载登录页，识别 APP 扫码登录和各种按钮 ---")
                val url3 = "https://www.zhipin.com/web/user/?ka=header-login"
                println("正在导航到: $url3")
                page.loadUrl(url3)
                println("等待页面加载 (8秒)...")
                Thread.sleep(8000)

                val rawTree3 = page.getRawAxTree()
                val cleanYaml3 = page.snapshot(clean = true)

                File("debug_raw_3.json").writeText(Json { prettyPrint = true }.encodeToString(AxTreeData.serializer(), rawTree3))
                File("debug_clean_3.yaml").writeText(cleanYaml3)
                println("已保存: debug_raw_3.json & debug_clean_3.yaml")

                val scanElements = rawTree3.nodes.filter { it.text.contains("扫码") || it.text.contains("APP") || it.text.contains("二维码") }
                println("查找到的扫码相关节点数: ${scanElements.size}")
                scanElements.forEach {
                    println("  [${it.refid}] text='${it.text}' role='${it.role}' tagName='${it.tagName}' isVisible=${it.isVisible}")
                }

                val allButtons = rawTree3.nodes.filter { it.role.lowercase() == "button" || it.tagName == "button" || it.text.contains("登录") || it.text.contains("注册") }
                println("查找到的登录/按钮相关节点数: ${allButtons.size}")
                allButtons.forEach {
                    println("  [${it.refid}] text='${it.text}' role='${it.role}' tagName='${it.tagName}' isVisible=${it.isVisible}")
                }

            } catch (e: Exception) {
                println("[Runner] ❌ 执行调试过程中发生异常:")
                e.printStackTrace()
            } finally {
                println("\n[Runner] 调试完毕。正在释放资源并退出...")
                KBrowser.shutdown()
                frame?.dispose()
                exitProcess(0)
            }
        }
    }
}

// 辅助扩展函数
private fun AxNode.placeholder(): String = attributes["placeholder"] ?: ""

private fun AxTreeData.findParentRefId(childRefId: String): String? {
    // 简单查找 childIds 里含有该 refid 的父节点
    return nodes.firstOrNull { it.childIds.contains(childRefId) }?.refid
}
