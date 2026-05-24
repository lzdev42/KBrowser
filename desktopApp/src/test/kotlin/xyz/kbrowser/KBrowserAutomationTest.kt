package xyz.kbrowser

import kotlinx.coroutines.runBlocking
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.KBProfile
import xyz.kbrowser.webview.SnapshotType
import xyz.kbrowser.webview.getCleanedSnapshot
import xyz.kbrowser.webview.getViewportSnapshot
import java.io.File
import kotlin.system.exitProcess

fun main() {
    // 强制关闭新版 JCEF 的 Chrome Runtime 原生窗口模式，使用纯渲染 Alloy 模式
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== 正在启动 KBrowser 协程自动化测试 ======")

    runBlocking {
        try {
            val profile = KBProfile("test_isolation_profile")
            
            println("[测试] 正在使用 KBrowser 新建后台隐式自动化页面 (无头 OSR)...")
            val page = KBrowser.newPage("https://example.com", profile)

            println("[测试] 正在等待网页完全加载...")
            page.loadUrl("https://example.com")
            println("[测试] 网页加载成功！")

            println("[测试] 正在提取全量 DOM 原始 Snapshot 快照...")
            val rawTree = page.getSnapshot(SnapshotType.RAW)
            println("[测试] 原始 Snapshot 快照提取成功！总节点数: ${rawTree.totalElements}")

            println("[测试] 正在执行纯 Kotlin 算力清洗白名单与过滤信噪比...")
            val cleanedTree = page.getSnapshot(SnapshotType.CLEANED)
            println("[测试] 清洗完成！剩余可见节点数: ${cleanedTree.totalElements}")

            println("[测试] 正在执行几何坐标碰撞裁剪（计算视口内节点）...")
            val viewportTree = page.getSnapshot(SnapshotType.VIEWPORT)
            println("[测试] 视口裁剪完成！视口内节点数: ${viewportTree.totalElements}")

            // 输出几个提取出来的节点结构以作演示
            println("[测试] 前五个视口内清洗后的节点示例:")
            viewportTree.nodes.take(5).forEach { node ->
                println(" - [${node.tagName.uppercase()}] refid=${node.refid}, text='${node.text}', coordinates=(${node.x}, ${node.y}), size=${node.width}x${node.height}")
            }

            // 保存结果文件用于审计
            val file = File("cleaned_viewport_snapshot.yaml")
            file.writeText(viewportTree.nodes.joinToString("\n") { 
                "${it.tagName}: { refid: ${it.refid}, text: \"${it.text}\", pos: [${it.x}, ${it.y}] }"
            })
            println("[测试] 测试结果已成功保存到: ${file.absolutePath}")

            println("[测试] 正在一键销毁全局引擎...")
            KBrowser.shutdown()
            println("====== KBrowser 协程自动化测试顺利通过 ======")
            exitProcess(0)
        } catch (e: Exception) {
            println("[测试] ❌ 自动化测试执行出错:")
            e.printStackTrace()
            exitProcess(1)
        }
    }
}
