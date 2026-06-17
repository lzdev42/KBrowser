package xyz.kbrowser.webview

import xyz.kbrowser.jcef.KBCefAxTreeFetcher
import xyz.kbrowser.jcef.KBCefLocatorImpl
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString

fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("\n========================================================")
    println("          KBrowser Locator Debug Test                  ")
    println("========================================================\n")

    val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
    KBrowser.setConfigPath(storageDir)
    runBlocking { initializeKBrowser() }

    println("[Debugger] 额外等待 CefApp 完全初始化 (5s)...")
    Thread.sleep(5000)

    val profile = KBProfile("debug_profile", "$storageDir/debug_profile")
    val webView = JvmWebView(null, profile = profile, isHeadless = false)

    var frame: JFrame? = null
    SwingUtilities.invokeLater {
        frame = JFrame("KBrowser Locator Debugger").apply {
            defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            setSize(1280, 800)
            contentPane.add(webView.browser.getComponent())
            isVisible = true
        }
    }

    thread(name = "debug-test") {
        runBlocking {
            try {
                val page = KBPage(webView)
                KBrowser.registerPage(page)

                val url = "https://ctext.org/wiki.pl?if=gb&chapter=357161&remap=gb"
                println("正在直接导航到古文正文页面: $url")
                page.loadUrl(url)
                println("等待正文页面加载 (10秒)...")
                Thread.sleep(10000)

                // ── 1. 抓取原始 Accessibility.getFullAXTree ──
                println("\n[1] 抓取原始 Accessibility.getFullAXTree...")
                val devTools = webView.browser.getCefBrowser().devToolsClient
                if (devTools != null && !devTools.isClosed) {
                    try {
                        val rawJson = devTools.executeDevToolsMethod("Accessibility.getFullAXTree", "{}")
                            .get(20, TimeUnit.SECONDS)
                        if (rawJson != null) {
                            File("ctext_axtree_raw.json").writeText(rawJson)
                            println("原始 AXTree 保存成功: ctext_axtree_raw.json")
                        } else {
                            println("❌ 获取的原始 AXTree 为 null")
                        }
                    } catch (e: Exception) {
                        println("❌ 获取原始 AXTree 发生异常:")
                        e.printStackTrace()
                    }
                } else {
                    println("❌ DevTools 客户端不可用或已关闭")
                }

                // ── 2. 抓取 KBCefAxTreeFetcher 转换后的 AxTreeData ──
                println("\n[2] 抓取 KBCefAxTreeFetcher 转换后的 AxTreeData...")
                try {
                    val processedTree = page.getRawAxTree()
                    val prettyJson = Json { prettyPrint = true }.encodeToString(AxTreeData.serializer(), processedTree)
                    File("ctext_axtree_processed.json").writeText(prettyJson)
                    println("处理后的 AxTreeData 保存成功: ctext_axtree_processed.json")
                } catch (e: Exception) {
                    println("❌ 获取处理后的 AxTreeData 发生异常:")
                    e.printStackTrace()
                }

                // ── 3. 测试 KBLocator 定位器 ──
                println("\n[3] 开始测试 KBLocator 定位器...")

                // 我们可以使用不同的选择器来测试
                val selectorsToTest = listOf(
                    "Wiki",
                    "简体",
                    "繁体",
                    "Library",
                    "中國哲學書電子化計劃"
                )

                for (text in selectorsToTest) {
                    println("\n--- 测试文本: '$text' ---")

                    // A. 使用 getByText
                    println("A. 测试 page.getByText('$text', exact = false):")
                    try {
                        val locator = page.getByText(text, exact = false)
                        val count = locator.count()
                        println("  -> 匹配数量: $count")
                        if (count > 0) {
                            val txt = locator.getText()
                            val bbox = locator.boundingBox()
                            println("  -> 匹配成功! text='$txt', boundingBox=$bbox")
                        }
                    } catch (e: Exception) {
                        println("  -> ❌ getByText 失败: ${e.message}")
                    }

                    // B. 使用 getByRole
                    println("B. 测试 page.getByRole('link', '$text'):")
                    try {
                        val locator = page.getByRole("link", text)
                        val count = locator.count()
                        println("  -> 匹配数量: $count")
                        if (count > 0) {
                            val txt = locator.getText()
                            val bbox = locator.boundingBox()
                            println("  -> 匹配成功! text='$txt', boundingBox=$bbox")
                        }
                    } catch (e: Exception) {
                        println("  -> ❌ getByRole 失败: ${e.message}")
                    }
                }

                // C. 测试 CSS 选择器与 XPath 选择器的 getText() 行为
                println("\n--- [测试] CSS 和 XPath 定位器的 getText() 表现 ---")
                
                println("测试 page.locator('css=a').first():")
                try {
                    val locator = page.locator("css=a").first()
                    val count = locator.count()
                    println("  -> 匹配数量: $count")
                    if (count > 0) {
                        val txt = locator.getText()
                        println("  -> 匹配到的第一个链接的 text = '$txt'")
                    }
                    if (count > 5) {
                        val locator5 = page.locator("css=a").nth(5)
                        val txt5 = locator5.getText()
                        val href5 = locator5.getAttribute("href")
                        println("  -> 匹配到的第6个链接: text = '$txt5', href = '$href5'")
                    }
                } catch(e: Exception) {
                    println("  -> ❌ css=a 失败: ${e.message}")
                }

                println("测试 page.locator('xpath=//a').first():")
                try {
                    val locator = page.locator("xpath=//a").first()
                    val count = locator.count()
                    println("  -> 匹配数量: $count")
                    if (count > 0) {
                        val txt = locator.getText()
                        println("  -> 匹配到的第一个链接的 text = '$txt'")
                    }
                } catch(e: Exception) {
                    println("  -> ❌ xpath=//a 失败: ${e.message}")
                }

                // D. 测试 getAttribute() 表现
                println("\n--- [测试] CSS 和 XPath 定位器的 getAttribute('href') 表现 ---")
                try {
                    val locator = page.locator("css=a").first()
                    val href = locator.getAttribute("href")
                    println("  -> css=a.first() 的 href = '$href'")
                } catch(e: Exception) {
                    println("  -> ❌ getAttribute 失败: ${e.message}")
                }

                // E. 测试 KBPage.snapshot(clean = false) 表现
                println("\n--- [测试] KBPage.snapshot(clean = false) 表现 ---")
                try {
                    val snapshotData = page.snapshot(clean = false)
                    println("  -> snapshot(clean = false) 前 500 字符: \n${snapshotData.take(500)}...")
                } catch(e: Exception) {
                    println("  -> ❌ snapshot 失败: ${e.message}")
                }
                
                // F. 摘录文章详细内容 (用户指定的真实需求)
                println("\n--- [测试] 直接抓取古文详细内容 ---")
                try {
                    // 正文部分通常在 id 为 "content" 的区域，为了确保拿到纯文本，我们直接拿 body 或者 #content
                    val contentLocator = page.locator("#content").first()
                    val realArticleText = contentLocator.getText()
                    
                    println("  -> 成功抓取正文页面！以下为真实古文正文详细内容的摘录（前 1500 字）:")
                    println("=========================================")
                    println(realArticleText.take(1500))
                    println("=========================================")
                } catch(e: Exception) {
                    println("  -> ❌ 抓取真实正文内容失败: ${e.message}")
                    e.printStackTrace()
                }

                // 结束测试，去掉不需要的内部诊断代码，以防在新页面下因为 ID 变更报错
            } catch (e: Exception) {
                println("[Debugger] ❌ 执行过程中发生错误:")
                e.printStackTrace()
            } finally {
                println("\n[Debugger] 排查完毕。正在释放资源并退出...")
                KBrowser.shutdown()
                frame?.dispose()
                exitProcess(0)
            }
        }
    }
}
